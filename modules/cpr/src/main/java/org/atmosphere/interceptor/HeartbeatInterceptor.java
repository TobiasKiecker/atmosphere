/*
 * Copyright 2013 Jeanfrancois Arcand
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.interceptor;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsyncIOInterceptorAdapter;
import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereInterceptorWriter;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResource.TRANSPORT;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.util.ExecutorsFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * An interceptor that send whitespace every 30 seconds.
 *
 * @author Jeanfrancois Arcand
 */
public class HeartbeatInterceptor extends AtmosphereInterceptorAdapter {

    public final static String HEARTBEAT_INTERVAL_IN_SECONDS = HeartbeatInterceptor.class.getName() + ".heartbeatFrequencyInSeconds";
    public final static String INTERCEPTOR_ADDED = HeartbeatInterceptor.class.getName();

    private static final Logger logger = LoggerFactory.getLogger(HeartbeatInterceptor.class);
    private ScheduledExecutorService heartBeat;
    private static String paddingText;
    private int heartbeatFrequencyInSeconds = 30;

    static {
        StringBuffer whitespace = new StringBuffer();
        for (int i = 0; i < 1024; i++) {
            whitespace.append(" ");
        }
        whitespace.append("\n");
        paddingText = whitespace.toString();
    }

    public HeartbeatInterceptor paddingText(String paddingText) {
        this.paddingText = paddingText;
        return this;
    }

    @Override
    public void configure(AtmosphereConfig config) {
        String s = config.getInitParameter(HEARTBEAT_INTERVAL_IN_SECONDS);
        if (s != null) {
            heartbeatFrequencyInSeconds = Integer.valueOf(s);
        }
        heartBeat = ExecutorsFactory.getScheduler(config);
    }

    @Override
    public Action inspect(final AtmosphereResource r) {

        final AtmosphereResponse response = r.getResponse();
        final AtmosphereRequest request = r.getRequest();


        super.inspect(r);

        AsyncIOWriter writer = response.getAsyncIOWriter();
        if (AtmosphereInterceptorWriter.class.isAssignableFrom(writer.getClass()) && r.getRequest().getAttribute(INTERCEPTOR_ADDED) == null) {
            AtmosphereInterceptorWriter.class.cast(writer).interceptor(new AsyncIOInterceptorAdapter() {

                @Override
                public byte[] transformPayload(AtmosphereResponse response, byte[] responseDraft, byte[] data) throws IOException {
                    cancelF(request);
                    return responseDraft;
                }

                @Override
                public void postPayload(final AtmosphereResponse response, byte[] data, int offset, int length) {
                    logger.trace("Scheduling heartbeat for {}", r.uuid());

                    clock(r, request, response);
                }
            });
            r.getRequest().setAttribute(INTERCEPTOR_ADDED, Boolean.TRUE);
        }
        return Action.CONTINUE;
    }

    void cancelF(AtmosphereRequest request) {
        Future<?> f = (Future<?>) request.getAttribute("heartbeat.future");
        if (f != null) f.cancel(false);
        request.removeAttribute("heartbeat.future");
    }

    public HeartbeatInterceptor clock(final AtmosphereResource r, final AtmosphereRequest request, final AtmosphereResponse response) {
        request.setAttribute("heartbeat.future", heartBeat.schedule(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                logger.trace("Writing heartbeat for {}", r.uuid());
                if (r.isSuspended()) {
                    try {
                        response.write(paddingText, false);
                        if (r.transport().equals(TRANSPORT.LONG_POLLING) || r.transport().equals(TRANSPORT.JSONP)) {
                            r.resume();
                        } else {
                            response.flushBuffer();
                        }
                    } catch (Throwable t) {
                        logger.trace("{}", r.uuid(), t);
                        try {
                            AtmosphereResourceImpl.class.cast(r).cancel();
                            r.notifyListeners(new AtmosphereResourceEventImpl(AtmosphereResourceImpl.class.cast(r), true, false));
                        } catch (IOException e) {
                            logger.trace("{}", e);
                        }
                        ;
                        cancelF(request);
                    }
                } else {
                    cancelF(request);
                }
                return null;
            }
        }, heartbeatFrequencyInSeconds, TimeUnit.SECONDS));

        return this;
    }

    @Override
    public String toString() {
        return "Heartbeat Interceptor Support";
    }
}
