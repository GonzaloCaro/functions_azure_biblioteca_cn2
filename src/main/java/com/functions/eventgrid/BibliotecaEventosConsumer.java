package com.functions.eventgrid;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.EventGridTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;

public class BibliotecaEventosConsumer {

    @FunctionName("OnPrestamoCreado")
    public void onPrestamoCreado(
            @EventGridTrigger(name = "event") String eventContent,
            final ExecutionContext context) {
        try {
            JsonObject event = JsonParser.parseString(eventContent).getAsJsonObject();
            String subject = event.has("subject") ? event.get("subject").getAsString() : "";
            String data = event.has("data") ? event.get("data").toString() : "{}";
            context.getLogger().info("[OnPrestamoCreado] Subject: " + subject + " | Data: " + data);
            // Extensible: enviar notificación, registrar auditoría, etc.
        } catch (Exception e) {
            context.getLogger().severe("[OnPrestamoCreado] Error: " + e.getMessage());
        }
    }

    @FunctionName("OnPrestamoDevuelto")
    public void onPrestamoDevuelto(
            @EventGridTrigger(name = "event") String eventContent,
            final ExecutionContext context) {
        try {
            JsonObject event = JsonParser.parseString(eventContent).getAsJsonObject();
            String subject = event.has("subject") ? event.get("subject").getAsString() : "";
            String data = event.has("data") ? event.get("data").toString() : "{}";
            context.getLogger().info("[OnPrestamoDevuelto] Subject: " + subject + " | Data: " + data);
            // Extensible: actualizar reportes, liberar reservas, etc.
        } catch (Exception e) {
            context.getLogger().severe("[OnPrestamoDevuelto] Error: " + e.getMessage());
        }
    }

    @FunctionName("OnLibroCreado")
    public void onLibroCreado(
            @EventGridTrigger(name = "event") String eventContent,
            final ExecutionContext context) {
        try {
            JsonObject event = JsonParser.parseString(eventContent).getAsJsonObject();
            String subject = event.has("subject") ? event.get("subject").getAsString() : "";
            String data = event.has("data") ? event.get("data").toString() : "{}";
            context.getLogger().info("[OnLibroCreado] Subject: " + subject + " | Data: " + data);
            // Extensible: indexar catálogo, notificar adquisiciones, etc.
        } catch (Exception e) {
            context.getLogger().severe("[OnLibroCreado] Error: " + e.getMessage());
        }
    }
}
