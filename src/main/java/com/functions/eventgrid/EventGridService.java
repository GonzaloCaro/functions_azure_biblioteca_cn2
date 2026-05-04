package com.functions.eventgrid;

import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.util.BinaryData;
import com.azure.messaging.eventgrid.EventGridEvent;
import com.azure.messaging.eventgrid.EventGridPublisherClient;
import com.azure.messaging.eventgrid.EventGridPublisherClientBuilder;

import java.util.List;
import java.util.logging.Logger;

public class EventGridService {

    private static volatile EventGridPublisherClient<EventGridEvent> client;

    private static EventGridPublisherClient<EventGridEvent> getClient() {
        if (client == null) {
            synchronized (EventGridService.class) {
                if (client == null) {
                    String endpoint = System.getenv("EVENT_GRID_TOPIC_ENDPOINT");
                    String key = System.getenv("EVENT_GRID_TOPIC_KEY");
                    if (endpoint == null || endpoint.isBlank() || key == null || key.isBlank()) {
                        throw new IllegalStateException(
                                "Las variables EVENT_GRID_TOPIC_ENDPOINT y EVENT_GRID_TOPIC_KEY son requeridas");
                    }
                    client = new EventGridPublisherClientBuilder()
                            .endpoint(endpoint)
                            .credential(new AzureKeyCredential(key))
                            .buildEventGridEventPublisherClient();
                }
            }
        }
        return client;
    }

    public static void publicarEvento(String subject, String eventType, Object data, Logger logger) {
        try {
            EventGridEvent event = new EventGridEvent(subject, eventType, BinaryData.fromObject(data), "1.0");
            getClient().sendEvents(List.of(event));
            logger.info("Evento publicado: " + eventType + " | Subject: " + subject);
        } catch (Exception e) {
            logger.severe("Error al publicar evento " + eventType + ": " + e.getMessage());
        }
    }
}
