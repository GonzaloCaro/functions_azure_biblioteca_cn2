package com.functions.eventgrid;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.EventGridTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

public class BibliotecaEventosConsumer {

    private static final AtomicBoolean WALLET_READY = new AtomicBoolean(false);

    @FunctionName("OnPrestamoCreado")
    public void onPrestamoCreado(
            @EventGridTrigger(name = "event") String eventContent,
            final ExecutionContext context) {
        try (Connection connection = openConnection()) {
            JsonObject event = JsonParser.parseString(eventContent).getAsJsonObject();
            JsonObject data = event.get("data").getAsJsonObject();
            
            if (data.has("id_libro")) {
                int idLibro = data.get("id_libro").getAsInt();
                context.getLogger().info("[OnPrestamoCreado] Reduciendo stock para libro ID: " + idLibro);
                actualizarStockLibro(connection, idLibro, -1);
            }
        } catch (Exception e) {
            context.getLogger().severe("[OnPrestamoCreado] Error: " + e.getMessage());
        }
    }

    @FunctionName("OnEstudianteEliminado")
    public void onEstudianteEliminado(
            @EventGridTrigger(name = "event") String eventContent,
            final ExecutionContext context) {
        try (Connection connection = openConnection()) {
            JsonObject event = JsonParser.parseString(eventContent).getAsJsonObject();
            JsonObject data = event.get("data").getAsJsonObject();
            
            if (data.has("id_estudiante")) {
                int idEstudiante = data.get("id_estudiante").getAsInt();
                context.getLogger().info("[OnEstudianteEliminado] Eliminando préstamos para estudiante ID: " + idEstudiante);
                eliminarPrestamosPorEstudiante(connection, idEstudiante);
            }
        } catch (Exception e) {
            context.getLogger().severe("[OnEstudianteEliminado] Error: " + e.getMessage());
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

    @FunctionName("OnPrestamoDevuelto")
    public void onPrestamoDevuelto(
            @EventGridTrigger(name = "event") String eventContent,
            final ExecutionContext context) {
        try (Connection connection = openConnection()) {
            JsonObject event = JsonParser.parseString(eventContent).getAsJsonObject();
            JsonObject data = event.get("data").getAsJsonObject();
            
            if (data.has("id_libro")) {
                int idLibro = data.get("id_libro").getAsInt();
                context.getLogger().info("[OnPrestamoDevuelto] Incrementando stock para libro ID: " + idLibro);
                actualizarStockLibro(connection, idLibro, 1);
            }
        } catch (Exception e) {
            context.getLogger().severe("[OnPrestamoDevuelto] Error: " + e.getMessage());
        }
    }

    private void actualizarStockLibro(Connection conn, int idLibro, int cambio) throws SQLException {
        String sql = "UPDATE libro SET stock = stock + ? WHERE id_libro = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, cambio);
            pstmt.setInt(2, idLibro);
            pstmt.executeUpdate();
        }
    }

    private void eliminarPrestamosPorEstudiante(Connection conn, int idEstudiante) throws SQLException {
        String sql = "DELETE FROM prestamo WHERE id_estudiante = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, idEstudiante);
            pstmt.executeUpdate();
        }
    }

    // --- Lógica de Conexión a Base de Datos (Wallet Soporte) ---

    private Connection openConnection() throws Exception {
        prepareWalletIfNeeded();

        String dbUrl = envOrDefault("BIBLIOTECA_DB_URL", "jdbc:oracle:thin:@cxtjowjkr0mdsxfa_high");
        String dbUser = envOrDefault("BIBLIOTECA_DB_USER", "biblioteca_CN2");
        String dbPassword = envOrDefault("BIBLIOTECA_DB_PASSWORD", "Caroorion1780*");

        Properties props = new Properties();
        props.put("user", dbUser);
        props.put("password", dbPassword);

        String tnsAdmin = System.getenv("TNS_ADMIN");
        if (tnsAdmin != null && !tnsAdmin.isBlank()) {
            props.put("oracle.net.tns_admin", tnsAdmin);
            props.put("oracle.net.wallet_location", "(SOURCE=(METHOD=file)(METHOD_DATA=(DIRECTORY=" + tnsAdmin + ")))");
        }

        return DriverManager.getConnection(dbUrl, props);
    }

    private void prepareWalletIfNeeded() throws IOException {
        if (WALLET_READY.get()) {
            return;
        }

        String configuredTnsAdmin = System.getenv("TNS_ADMIN");
        if (configuredTnsAdmin != null && !configuredTnsAdmin.isBlank()) {
            WALLET_READY.set(true);
            return;
        }

        Path walletDir = Path.of(System.getProperty("java.io.tmpdir"), "wallet");
        Files.createDirectories(walletDir);

        String[] walletFiles = { "cwallet.sso", "tnsnames.ora", "sqlnet.ora", "ojdbc.properties" };
        for (String file : walletFiles) {
            try (InputStream is = getClass().getClassLoader().getResourceAsStream("wallet/" + file)) {
                if (is != null) {
                    Files.copy(is, walletDir.resolve(file), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        System.setProperty("oracle.net.tns_admin", walletDir.toString());
        WALLET_READY.set(true);
    }

    private String envOrDefault(String envName, String defaultValue) {
        String value = System.getenv(envName);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
