package org.wso2.carbon.apimgt.tracing;

import io.opentracing.Span;
import io.opentracing.Tracer;
import org.wso2.carbon.apimgt.impl.APIManagerConfiguration;

public abstract class OpenTracer {

    public abstract Tracer getTracer(String tracerName, APIManagerConfiguration configuration, String serviceName);

    public abstract String getName();

    public static TracingSpan startSpan(String spanName, TracingSpan parentSpan, Tracer tracer) {

        if (parentSpan == null) {
            Span span = tracer.buildSpan(spanName).start();
            return new TracingSpan(span);

        } else {
            Span childSpan = tracer.buildSpan(spanName).asChildOf(parentSpan.getSpan()).start();
            return new TracingSpan(childSpan);
        }
    }

    public static void finishSpan(TracingSpan span) {
        span.getSpan().finish();
    }

}