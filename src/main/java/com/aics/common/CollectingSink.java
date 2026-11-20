package com.aics.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 收集型 sink：评测与契约测试用它替代浏览器，验证事件顺序而不依赖 HTTP。
 */
public class CollectingSink implements ChatSink {

    public record Event(String name, Map<String, Object> payload) {
    }

    private final List<Event> events = Collections.synchronizedList(new ArrayList<>());

    @Override
    @SuppressWarnings("unchecked")
    public void send(String event, Object payload) {
        events.add(new Event(event, payload instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of()));
    }

    @Override
    public boolean isClosed() {
        return false;
    }

    public List<Event> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    public List<String> names() {
        return events().stream().map(Event::name).toList();
    }

    public Event first(String eventName) {
        return events().stream().filter(e -> e.name().equals(eventName)).findFirst().orElse(null);
    }

    public String concatenatedDelta() {
        StringBuilder sb = new StringBuilder();
        for (Event e : events()) {
            if ("delta".equals(e.name())) {
                Object text = e.payload().get("text");
                if (text != null) {
                    sb.append(text);
                }
            }
        }
        return sb.toString();
    }
}
