package org.eclipse.californium.proxy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.network.config.NetworkConfig;

import cz.msebera.android.httpclient.HttpException;
import cz.msebera.android.httpclient.HttpRequest;
import cz.msebera.android.httpclient.HttpRequestInterceptor;
import cz.msebera.android.httpclient.HttpResponse;
import cz.msebera.android.httpclient.HttpResponseInterceptor;
import cz.msebera.android.httpclient.HttpStatus;
import cz.msebera.android.httpclient.HttpVersion;
import cz.msebera.android.httpclient.StatusLine;
import cz.msebera.android.httpclient.client.protocol.RequestAcceptEncoding;
import cz.msebera.android.httpclient.client.protocol.ResponseContentEncoding;
import cz.msebera.android.httpclient.entity.StringEntity;
import cz.msebera.android.httpclient.impl.DefaultConnectionReuseStrategy;
import cz.msebera.android.httpclient.impl.EnglishReasonPhraseCatalog;
import cz.msebera.android.httpclient.impl.nio.DefaultHttpServerIODispatch;
import cz.msebera.android.httpclient.impl.nio.DefaultNHttpServerConnection;
import cz.msebera.android.httpclient.impl.nio.DefaultNHttpServerConnectionFactory;
import cz.msebera.android.httpclient.impl.nio.reactor.DefaultListeningIOReactor;
import cz.msebera.android.httpclient.message.BasicStatusLine;
import cz.msebera.android.httpclient.nio.NHttpConnectionFactory;
import cz.msebera.android.httpclient.nio.protocol.BasicAsyncRequestConsumer;
import cz.msebera.android.httpclient.nio.protocol.BasicAsyncRequestHandler;
import cz.msebera.android.httpclient.nio.protocol.HttpAsyncExchange;
import cz.msebera.android.httpclient.nio.protocol.HttpAsyncRequestConsumer;
import cz.msebera.android.httpclient.nio.protocol.HttpAsyncRequestHandler;
import cz.msebera.android.httpclient.nio.protocol.HttpAsyncRequestHandlerRegistry;
import cz.msebera.android.httpclient.nio.protocol.HttpAsyncService;
import cz.msebera.android.httpclient.nio.reactor.IOEventDispatch;
import cz.msebera.android.httpclient.nio.reactor.ListeningIOReactor;
import cz.msebera.android.httpclient.params.CoreConnectionPNames;
import cz.msebera.android.httpclient.params.CoreProtocolPNames;
import cz.msebera.android.httpclient.params.HttpParams;
import cz.msebera.android.httpclient.params.SyncBasicHttpParams;
import cz.msebera.android.httpclient.protocol.HttpContext;
import cz.msebera.android.httpclient.protocol.HttpProcessor;
import cz.msebera.android.httpclient.protocol.HttpRequestHandler;
import cz.msebera.android.httpclient.protocol.ImmutableHttpProcessor;
import cz.msebera.android.httpclient.protocol.ResponseConnControl;
import cz.msebera.android.httpclient.protocol.ResponseContent;
import cz.msebera.android.httpclient.protocol.ResponseDate;
import cz.msebera.android.httpclient.protocol.ResponseServer;


/**
 * Class encapsulating the logic of a http server. The class create a receiver
 * thread that it is always blocked on the listen primitive. For each connection
 * this thread creates a new thread that handles the client/server dialog.
 */
public class HttpStack {
	
	private static final Logger LOGGER = Logger.getLogger(HttpStack.class.getCanonicalName());
	
	private static final Response Response_NULL = new Response(null); // instead of Response.NULL // TODO
	
	private static final int SOCKET_TIMEOUT = NetworkConfig.getStandard().getInt(
			NetworkConfig.Keys.HTTP_SERVER_SOCKET_TIMEOUT);
	private static final int SOCKET_BUFFER_SIZE = NetworkConfig.getStandard().getInt(
			NetworkConfig.Keys.HTTP_SERVER_SOCKET_BUFFER_SIZE);
	private static final int GATEWAY_TIMEOUT = SOCKET_TIMEOUT * 3 / 4;
	private static final String SERVER_NAME = "Californium Http Proxy";
	
	/**
	 * Resource associated with the proxying behavior. If a client requests
	 * resource indicated by
	 * http://proxy-address/PROXY_RESOURCE_NAME/coap-server, the proxying
	 * handler will forward the request desired coap server.
	 */
	private static final String PROXY_RESOURCE_NAME = "proxy";

	/**
	 * The resource associated with the local resources behavior. If a client
	 * requests resource indicated by
	 * http://proxy-address/LOCAL_RESOURCE_NAME/coap-resource, the proxying
	 * handler will forward the request to the local resource requested.
	 */
	public static final String LOCAL_RESOURCE_NAME = "local";

	private final ConcurrentHashMap<Request, Exchanger<Response>> exchangeMap = new ConcurrentHashMap<Request, Exchanger<Response>>();

	private RequestHandler requestHandler;
	
	/**
	 * Instantiates a new http stack on the requested port. It creates an http
	 * listener thread on the port.
	 * 
	 * @param httpPort
	 *            the http port
	 * @throws IOException
	 *             Signals that an I/O exception has occurred.
	 */
	public HttpStack(int httpPort) throws IOException {
		new HttpServer(httpPort);
	}

	/**
	 * Checks if a thread is waiting for the arrive of a specific response.
	 * 
	 * @param request
	 *            the request
	 * @return true, if is waiting
	 */
	public boolean isWaitingRequest(Request request) {

		// DEBUG
		// System.out.println(request.hashCode());
		// request.prettyPrint();
		//
		// System.out.println(responseMap.get(request) != null);
		// System.out.println(semaphoreMap.get(request) != null);
		//
		// for (Request r : responseMap.keySet()) {
		// System.out.println(r.hashCode());
		// r.prettyPrint();
		// }
		//
		// for (Request r : semaphoreMap.keySet()) {
		// System.out.println(r.hashCode());
		// r.prettyPrint();
		// }

		// check the presence of the key in both maps
		// TODO check how much is this operation heavy
		// return responseMap.containsKey(request) &&
		// semaphoreMap.containsKey(request);

		return exchangeMap.containsKey(request);
	}

	/**
	 * Send simple http response.
	 * 
	 * @param httpExchange
	 *            the http exchange
	 * @param httpCode
	 *            the http code
	 */
	private void sendSimpleHttpResponse(HttpAsyncExchange httpExchange, int httpCode) {
		// get the empty response from the exchange
		HttpResponse httpResponse = httpExchange.getResponse();

		// create and set the status line
		StatusLine statusLine = new BasicStatusLine(HttpVersion.HTTP_1_1, httpCode, EnglishReasonPhraseCatalog.INSTANCE.getReason(httpCode, Locale.ENGLISH));
		httpResponse.setStatusLine(statusLine);

		// send the error response
		httpExchange.submitResponse();
	}

	protected void doSendResponse(Request request, Response response) throws IOException {
		// the http stack is intended to send back only coap responses

		// retrieve the request linked to the response
//		if (Bench_Help.DO_LOG) 
			LOGGER.fine("Handling response for request: " + request);

		// fill the exchanger with the incoming response
		Exchanger<Response> exchanger = exchangeMap.get(request);
		if (exchanger != null) {
			try {
				exchanger.exchange(response);
//				if (Bench_Help.DO_LOG) 
					LOGGER.info("Exchanged correctly");
			} catch (InterruptedException e) {
				LOGGER.log(Level.WARNING, "Exchange interrupted", e);

				// remove the entry from the map
				exchangeMap.remove(request);
				return;
			}
		} else {
			LOGGER.warning("exchanger was null for request "+request+" with hash "+request.hashCode());
		}
	}
        /**
	 * The Class CoapResponseWorker. This thread request a response from the
         * lower layers. It is the producer of the producer/consumer pattern.
	 */
        private final class CoapRequestWorker extends Thread {
		private final Request coapRequest;

		/**
		 * Instantiates a new coap response worker.
		 *
		 * @param name
		 *            the name
		 * @param coapRequest
		 *            the coap request
		 */
		public CoapRequestWorker(String name, Request coapRequest) {
			super(name);
			this.coapRequest = coapRequest;
                }

		@Override
		public void run() {
                    doReceiveMessage(coapRequest);
                }
        }

	/**
	 * The Class CoapResponseWorker. This thread waits a response from the lower
	 * layers. It is the consumer of the producer/consumer pattern.
	 */
	private final class CoapResponseWorker extends Thread {
		private final HttpAsyncExchange httpExchange;
		private final HttpRequest httpRequest;
		private final Request coapRequest;
                private final Thread responseWorker;

		/**
		 * Instantiates a new coap response worker.
		 * 
		 * @param name
		 *            the name
		 * @param coapRequest
		 *            the coap request
		 * @param httpExchange
		 *            the http exchange
		 * @param httpRequest
		 *            the http request
                 * @param responseWorker
		 *            the coap response worker
		 */
		public CoapResponseWorker(String name, Request coapRequest, HttpAsyncExchange httpExchange, HttpRequest httpRequest, Thread responseWorker) {
			super(name);
			this.coapRequest = coapRequest;
			this.httpExchange = httpExchange;
			this.httpRequest = httpRequest;
                        this.responseWorker = responseWorker;
		}

		/*
		 * (non-Javadoc)
		 * @see java.lang.Thread#run()
		 */
		@Override
		public void run() {
			// get the exchanger
			Exchanger<Response> exchanger = exchangeMap.get(coapRequest);

			// if the map does not contain the key, send an error response
			if (exchanger == null) {
				LOGGER.warning("exchanger == null");
				sendSimpleHttpResponse(httpExchange, HttpStatus.SC_INTERNAL_SERVER_ERROR);
				return;
			}

			// get the response
			Response coapResponse = null;
			try {
				coapResponse = exchanger.exchange(Response_NULL, GATEWAY_TIMEOUT, TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				LOGGER.warning("Timeout occurred");
				// send the timeout error message
				sendSimpleHttpResponse(httpExchange, HttpTranslator.STATUS_TIMEOUT);
				return;
			} catch (InterruptedException e) {
				// if the thread is interrupted, terminate
				if (isInterrupted()) {
					LOGGER.warning("Thread interrupted");
					sendSimpleHttpResponse(httpExchange, HttpStatus.SC_INTERNAL_SERVER_ERROR);
					return;
				}
			} finally {
				// remove the entry from the map
				exchangeMap.remove(coapRequest);
                                // the producer thread was unable to deliver a response at time, so we kill it.
                                responseWorker.interrupt();
//				if (Bench_Help.DO_LOG) 
					LOGGER.finer("Entry removed from map");
			}

			if (coapResponse == null) {
				LOGGER.warning("No coap response");
				sendSimpleHttpResponse(httpExchange, HttpTranslator.STATUS_NOT_FOUND);
				return;
			}

			// get the sample http response
			HttpResponse httpResponse = httpExchange.getResponse();

			try {
				// translate the coap response in an http response
				HttpTranslator.getHttpResponse(httpRequest, coapResponse, httpResponse);

//				if (Bench_Help.DO_LOG) 
					LOGGER.finer("Outgoing http response: " + httpResponse.getStatusLine());
			} catch (TranslationException e) {
				LOGGER.warning("Failed to translate coap response to http response: " + e.getMessage());
				sendSimpleHttpResponse(httpExchange, HttpTranslator.STATUS_TRANSLATION_ERROR);
				return;
			}
			 httpResponse.setHeader("Access-Control-Allow-Methods", "GET,POST,HEAD,PUT,DELETE,OPTIONS");
		      httpResponse.setHeader("Access-Control-Allow-Origin", "*");
		      httpResponse.setHeader("Access-Control-Allow-Headers", "Access-Control-Allow-Origin, UTOKEN, DTOKEN,Accept, Origin, X-Requested-With, Content-Type, Last-Modified,Keep-Alive,User-Agent,If-Modified-Since,Cache-Control");
			// send the response
			httpExchange.submitResponse();
		}
	}

	private class HttpServer {

		public HttpServer(int httpPort) {
			// HTTP parameters for the server
			HttpParams params = new SyncBasicHttpParams();
			params.setIntParameter(CoreConnectionPNames.SO_TIMEOUT, SOCKET_TIMEOUT).setIntParameter(CoreConnectionPNames.SOCKET_BUFFER_SIZE, SOCKET_BUFFER_SIZE).setBooleanParameter(CoreConnectionPNames.TCP_NODELAY, true).setParameter(CoreProtocolPNames.ORIGIN_SERVER, SERVER_NAME);

			// Create HTTP protocol processing chain
			// Use standard server-side protocol interceptors
			HttpRequestInterceptor[] requestInterceptors = new HttpRequestInterceptor[] { new RequestAcceptEncoding() };
			HttpResponseInterceptor[] responseInterceptors = new HttpResponseInterceptor[] { new ResponseContentEncoding(), new ResponseDate(), new ResponseServer(), new ResponseContent(), new ResponseConnControl() };
			HttpProcessor httpProcessor = new ImmutableHttpProcessor(requestInterceptors, responseInterceptors);

			// Create request handler registry
			HttpAsyncRequestHandlerRegistry registry = new HttpAsyncRequestHandlerRegistry();

			// register the handler that will reply to the proxy requests
			registry.register("/" + PROXY_RESOURCE_NAME + "/*", new ProxyAsyncRequestHandler(PROXY_RESOURCE_NAME, true));
			// register the handler for the frontend
			registry.register("/" + LOCAL_RESOURCE_NAME + "/*", new ProxyAsyncRequestHandler(LOCAL_RESOURCE_NAME, false));
			// register the default handler for root URIs
			// wrapping a common request handler with an async request handler
			registry.register("*", new BasicAsyncRequestHandler(new BaseRequestHandler()));

			// Create server-side HTTP protocol handler
			HttpAsyncService protocolHandler = new HttpAsyncService(httpProcessor, new DefaultConnectionReuseStrategy(), registry, params);

			// Create HTTP connection factory
			NHttpConnectionFactory<DefaultNHttpServerConnection> connFactory = new DefaultNHttpServerConnectionFactory(params);

			// Create server-side I/O event dispatch
			final IOEventDispatch ioEventDispatch = new DefaultHttpServerIODispatch(protocolHandler, connFactory);

			final ListeningIOReactor ioReactor;
			try {
				// Create server-side I/O reactor
				ioReactor = new DefaultListeningIOReactor();
				// Listen of the given port
				LOGGER.info("HttpStack listening on port "+httpPort);
				ioReactor.listen(new InetSocketAddress(httpPort));

				// create the listener thread
				Thread listener = new Thread("HttpStack listener") {

					@Override
					public void run() {
						// Starts the reactor and initiates the dispatch of I/O
						// event notifications to the given IOEventDispatch.
						try {
							LOGGER.info("Submitted http listening to thread 'HttpStack listener'");

							ioReactor.execute(ioEventDispatch);
						} catch (IOException e) {
							LOGGER.severe("I/O Exception in HttpStack: " + e.getMessage());
						}

						LOGGER.info("Shutdown HttpStack");
					}
				};

				listener.setDaemon(false);
				listener.start();
				LOGGER.info("HttpStack started");
			} catch (IOException e) {
				LOGGER.severe("I/O error: " + e.getMessage());
			}
		}

		/**
		 * The Class BaseRequestHandler handles simples requests that do not
		 * need the proxying.
		 */
		private class BaseRequestHandler implements HttpRequestHandler {

			/*
			 * (non-Javadoc)
			 * @see
			 * cz.msebera.android.httpclient.protocol.HttpRequestHandler#handle(org.apache
			 * .http .HttpRequest, cz.msebera.android.httpclient.HttpResponse,
			 * cz.msebera.android.httpclient.protocol.HttpContext)
			 */
			public void handle(HttpRequest httpRequest, HttpResponse httpResponse, HttpContext httpContext) throws HttpException, IOException {
				httpResponse.setStatusCode(HttpStatus.SC_OK);
				httpResponse.setEntity(new StringEntity("Californium Proxy server"));

//				if (Bench_Help.DO_LOG) 
					LOGGER.finer("Root request handled");
			}
		}

		/**
		 * Class associated with the http service to translate the http requests
		 * in coap requests and to produce the http responses. Even if the class
		 * accepts a string indicating the name of the proxy resource, it is
		 * still thread-safe because the local resource is set in the
		 * constructor and then only read by the methods.
		 */
		private class ProxyAsyncRequestHandler implements
				HttpAsyncRequestHandler<HttpRequest> {

			private final String localResource;
			private final boolean proxyingEnabled;

			/**
			 * Instantiates a new proxy request handler.
			 * 
			 * @param localResource
			 *            the local resource
			 * @param proxyingEnabled
			 */
			public ProxyAsyncRequestHandler(String localResource, boolean proxyingEnabled) {
				super();

				this.localResource = localResource;
				this.proxyingEnabled = proxyingEnabled;
			}

			/*
			 * (non-Javadoc)
			 * @see
			 * cz.msebera.android.httpclient.nio.protocol.HttpAsyncRequestHandler#handle(java.
			 * lang.Object, cz.msebera.android.httpclient.nio.protocol.HttpAsyncExchange,
			 * cz.msebera.android.httpclient.protocol.HttpContext)
			 */
			public void handle(HttpRequest httpRequest, HttpAsyncExchange httpExchange, HttpContext httpContext) throws HttpException, IOException {
//				if (Bench_Help.DO_LOG) 
					LOGGER.finer("Incoming http request: " + httpRequest.getRequestLine());
					
				try {
				  // ** start 此处修改，处理options请求
				  String httpMethod = httpRequest.getRequestLine().getMethod().toLowerCase();
				  if(httpMethod.equals("options")){
				    handleOptions(httpRequest, httpExchange, httpContext);
				    return;
				  }
                 

				  // ** end 此处修改 
				  
					// translate the request in a valid coap request
					Request coapRequest = HttpTranslator.getCoapRequest(httpRequest, localResource, proxyingEnabled);
//					if (Bench_Help.DO_LOG) 
						LOGGER.info("Received HTTP request and translate to "+coapRequest);

					// fill the maps
					exchangeMap.put(coapRequest, new Exchanger<Response>());
//					if (Bench_Help.DO_LOG) 
						LOGGER.finer("Fill exchange with: " + coapRequest+" with hash="+coapRequest.hashCode());

					// We create two threads
                                        // The responseWorker will be in charge of producing a CoapResponse (producer)
                                        // The requestWorker will be in charge of using this response to return it to the client
                                        Thread requestWorker = new CoapRequestWorker("HttpStart Worker: consummer", coapRequest);
                                        Thread responseWorker = new CoapResponseWorker("HttpStack Worker: producer", coapRequest, httpExchange, httpRequest, requestWorker);

					// starting the producer and consummer thread
					requestWorker.start();
					responseWorker.start();

//					if (Bench_Help.DO_LOG) 
						LOGGER.finer("Started thread 'httpStack worker' to wait the response");

				} catch (InvalidMethodException e) {
					LOGGER.warning("Method not implemented" + e.getMessage());
					sendSimpleHttpResponse(httpExchange, HttpTranslator.STATUS_WRONG_METHOD);
					return;
				} catch (InvalidFieldException e) {
					LOGGER.warning("Request malformed" + e.getMessage());
					sendSimpleHttpResponse(httpExchange, HttpTranslator.STATUS_URI_MALFORMED);
					return;
				} catch (TranslationException e) {
					LOGGER.warning("Failed to translate the http request in a valid coap request: " + e.getMessage());
					sendSimpleHttpResponse(httpExchange, HttpTranslator.STATUS_TRANSLATION_ERROR);
					return;
                                }
			}

			/*
			 * (non-Javadoc)
			 * @see
			 * cz.msebera.android.httpclient.nio.protocol.HttpAsyncRequestHandler#processRequest
			 * (cz.msebera.android.httpclient.HttpRequest,
			 * cz.msebera.android.httpclient.protocol.HttpContext)
			 */
			public HttpAsyncRequestConsumer<HttpRequest> processRequest(HttpRequest httpRequest, HttpContext httpContext) throws HttpException, IOException {
				// Buffer request content in memory for simplicity
				return new BasicAsyncRequestConsumer();
			}
		}

		// 自定义的options请求处理方法
    public void handleOptions(HttpRequest httpRequest, HttpAsyncExchange httpExchange,
        HttpContext httpContext) {
   // get the empty response from the exchange
      HttpResponse httpResponse = httpExchange.getResponse();

      // create and set the status line
      StatusLine statusLine = new BasicStatusLine(HttpVersion.HTTP_1_1, HttpStatus.SC_OK, "OK" );
      httpResponse.setStatusLine(statusLine);
      httpResponse.setHeader("Access-Control-Allow-Methods", "GET,POST,HEAD,PUT,DELETE,OPTIONS");
      httpResponse.setHeader("Access-Control-Allow-Origin", "*");
      httpResponse.setHeader("Access-Control-Allow-Headers", "Access-Control-Allow-Origin, UTOKEN, DTOKEN,Accept, Origin, X-Requested-With, Content-Type, Last-Modified,Keep-Alive,User-Agent,If-Modified-Since,Cache-Control");
      // send the error response
      httpExchange.submitResponse();
      
      
    }

	}
	
	public void doReceiveMessage(Request request) {
		requestHandler.handleRequest(request);
	}

	public RequestHandler getRequestHandler() {
		return requestHandler;
	}

	public void setRequestHandler(RequestHandler requestHandler) {
		this.requestHandler = requestHandler;
	}

}
