package io.streamzi.eventflow.annotations;


/**
 * This interface defines an object that can receive cloud events
 *
 * @author hhiden
 */
public interface CloudEventProducerTarget {
    void send(Object event);

    void send(String key, Object event);
}
