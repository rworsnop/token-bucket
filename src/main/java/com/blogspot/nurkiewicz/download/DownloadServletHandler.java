package com.blogspot.nurkiewicz.download;

import com.blogspot.nurkiewicz.download.tokenbucket.TokenBucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.HttpRequestHandler;

import javax.annotation.Resource;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

import static org.apache.commons.lang.time.DateUtils.MILLIS_PER_HOUR;

/**
 * @author Tomasz Nurkiewicz
 * @since 04.03.11, 20:21
 */
@Service
@ManagedResource
public class DownloadServletHandler implements HttpRequestHandler {

	private static final Logger log = LoggerFactory.getLogger(DownloadServletHandler.class);

	@Resource
	private TokenBucket tokenBucket;

	private AtomicLong requestNo = new AtomicLong();

	@Resource
	private ThreadPoolTaskExecutor downloadWorkersPool;

	@Override
	public void handleRequest(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		long curRequestNo = requestNo.incrementAndGet();
		request.setAttribute(TokenBucket.REQUEST_NO, curRequestNo);
		log.info("Serving: {} ({})", request.getRequestURI(), curRequestNo);
//		final File file = new File(req.getRequestURI());
		final File file = new File("/home/dev/tmp/ehcache-1.6.2.jar");
		final BufferedInputStream input = new BufferedInputStream(new FileInputStream(file));
		response.setContentLength((int) file.length());
		final AsyncContext asyncContext = request.startAsync(request, response);
		asyncContext.setTimeout(MILLIS_PER_HOUR);
		final DownloadChunkTask task = new DownloadChunkTask(asyncContext, input, curRequestNo);
		asyncContext.addListener(task);
		downloadWorkersPool.submit(task);
	}

	private class DownloadChunkTask implements Callable<Void>, AsyncListener {

		private final BufferedInputStream fileInputStream;
		private final byte[] buffer = new byte[TokenBucket.TOKEN_PERMIT_SIZE];
		private final AsyncContext ctx;
		private final long requestNo;
		int chunkNo;

		public DownloadChunkTask(AsyncContext ctx, BufferedInputStream fileInputStream, long requestNo) throws IOException {
			this.ctx = ctx;
			this.requestNo = requestNo;
			this.fileInputStream = fileInputStream;
		}

		@Override
		public Void call() throws Exception {
			try {
				if (tokenBucket.tryTake(ctx.getRequest())) {
					sendChunkWorthOneToken();
				} else
					downloadWorkersPool.submit(this);
			} catch (Exception e) {
				log.error("Error while sending data chunk, aborting ("  + requestNo + ")", e);
				ctx.complete();
			}
			return null;
		}

		private void sendChunkWorthOneToken() throws IOException {
			log.trace("Sending chunk {} of request ({})", chunkNo++, requestNo);
			final int bytesCount = fileInputStream.read(buffer);
			ctx.getResponse().getOutputStream().write(buffer, 0, bytesCount);
			if (bytesCount < buffer.length) {
				ctx.complete();
			} else {
				downloadWorkersPool.submit(this);
			}
		}

		@Override
		public void onComplete(AsyncEvent asyncEvent) throws IOException {
			fileInputStream.close();
			tokenBucket.completed(ctx.getRequest());
			log.debug("Done: ({})", requestNo);
		}

		@Override
		public void onTimeout(AsyncEvent asyncEvent) throws IOException {
			log.warn("Asynchronous request timeout ({})", requestNo);
			onComplete(asyncEvent);
		}

		@Override
		public void onError(AsyncEvent asyncEvent) throws IOException {
			log.warn("Asynchronous request error (" + requestNo + ")", asyncEvent.getThrowable());
			onComplete(asyncEvent);
		}

		@Override
		public void onStartAsync(AsyncEvent asyncEvent) throws IOException {
		}
	}

	@ManagedAttribute
	public int getAwaitingChunks() {
		return downloadWorkersPool.getThreadPoolExecutor().getQueue().size();
	}

}
