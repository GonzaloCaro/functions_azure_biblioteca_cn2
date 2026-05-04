package com.functions.serverless;

import com.functions.eventgrid.EventGridService;
import com.google.gson.Gson;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

public class PrestamoAzureHandler {

    private static final Gson GSON = new Gson();
    private static final AtomicBoolean WALLET_READY = new AtomicBoolean(false);

    @FunctionName("PrestamoCrudHandler")
    public HttpResponseMessage handleLoanOperations(
            @HttpTrigger(
                    name = "request",
                    methods = { HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE },
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "prestamos")
            HttpRequestMessage<String> request,
            final ExecutionContext context) {

        try (Connection connection = openConnection()) {
            String httpMethod = request.getHttpMethod().name();

            switch (httpMethod.toUpperCase()) {
                case "GET":
                    List<Prestamo> prestamos = obtenerPrestamos(connection);
                    return jsonResponse(request, HttpStatus.OK, GSON.toJson(prestamos));

                case "POST":
                    String bodyPost = request.getBody();
                    Prestamo nuevoPrestamo = GSON.fromJson(bodyPost, Prestamo.class);
                    crearPrestamo(connection, nuevoPrestamo);
                    EventGridService.publicarEvento(
                            "/biblioteca/prestamos/" + nuevoPrestamo.id_estudiante + "/" + nuevoPrestamo.id_libro,
                            "Biblioteca.Prestamo.Creado",
                            nuevoPrestamo,
                            context.getLogger());
                    return jsonResponse(request, HttpStatus.CREATED, "{\"mensaje\": \"Préstamo registrado exitosamente\"}");

                case "PUT":
                    String bodyPut = request.getBody();
                    Prestamo prestamoReturn = GSON.fromJson(bodyPut, Prestamo.class);
                    devolverPrestamo(connection, prestamoReturn.id_prestamo);
                    EventGridService.publicarEvento(
                            "/biblioteca/prestamos/" + prestamoReturn.id_prestamo,
                            "Biblioteca.Prestamo.Devuelto",
                            prestamoReturn,
                            context.getLogger());
                    return jsonResponse(request, HttpStatus.OK, "{\"mensaje\": \"Libro devuelto y préstamo actualizado exitosamente\"}");

                case "DELETE":
                    String id = request.getQueryParameters().get("id");
                    if (id == null || id.isEmpty()) {
                        return jsonResponse(request, HttpStatus.BAD_REQUEST, "{\"error\": \"Se requiere parámetro id\"}");
                    }
                    eliminarPrestamo(connection, id);
                    return jsonResponse(request, HttpStatus.OK, "{\"mensaje\": \"Préstamo eliminado exitosamente\"}");

                default:
                    return jsonResponse(request, HttpStatus.METHOD_NOT_ALLOWED, "{\"error\": \"Método HTTP no permitido\"}");
            }
        } catch (Exception ex) {
            context.getLogger().severe("Error: " + ex.getMessage());
            return errorResponse(request, ex);
        }
    }

    private HttpResponseMessage jsonResponse(HttpRequestMessage<String> request, HttpStatus status, String body) {
        return request.createResponseBuilder(status)
                .header("Content-Type", "application/json")
                .body(body)
                .build();
    }

    private HttpResponseMessage errorResponse(HttpRequestMessage<String> request, Exception ex) {
        String escaped = ex.getMessage() == null ? "Error interno" : ex.getMessage().replace("\"", "\\\"");
        return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                .header("Content-Type", "application/json")
                .body("{\"error\": \"" + escaped + "\"}")
                .build();
    }

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

    private List<Prestamo> obtenerPrestamos(Connection conn) throws SQLException {
        String sql = "SELECT id_prestamo, id_estudiante, id_libro, fecha_prestamo, fecha_limite, fecha_devolucion, estado FROM prestamo ORDER BY id_prestamo ASC";
        List<Prestamo> prestamos = new ArrayList<>();

        try (PreparedStatement pstmt = conn.prepareStatement(sql); ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                Prestamo prestamo = new Prestamo();
                prestamo.id_prestamo = rs.getInt("id_prestamo");
                prestamo.id_estudiante = rs.getInt("id_estudiante");
                prestamo.id_libro = rs.getInt("id_libro");
                Date fechaPrestamo = rs.getDate("fecha_prestamo");
                Date fechaLimite = rs.getDate("fecha_limite");
                Date fechaDevolucion = rs.getDate("fecha_devolucion");
                prestamo.fecha_prestamo = fechaPrestamo != null ? fechaPrestamo.toString() : null;
                prestamo.fecha_limite = fechaLimite != null ? fechaLimite.toString() : null;
                prestamo.fecha_devolucion = fechaDevolucion != null ? fechaDevolucion.toString() : null;
                prestamo.estado = rs.getString("estado");
                prestamos.add(prestamo);
            }
        }
        return prestamos;
    }

    private void crearPrestamo(Connection conn, Prestamo prestamo) throws SQLException {
        String sql = "INSERT INTO prestamo (id_estudiante, id_libro, fecha_limite) VALUES (?, ?, ?)";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, prestamo.id_estudiante);
            pstmt.setInt(2, prestamo.id_libro);
            pstmt.setDate(3, Date.valueOf(prestamo.fecha_limite));
            pstmt.executeUpdate();
        }
    }

    private void devolverPrestamo(Connection conn, Integer idPrestamo) throws SQLException {
        String sql = "UPDATE prestamo SET estado = 'DEVUELTO', fecha_devolucion = SYSDATE WHERE id_prestamo = ?";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, idPrestamo);
            pstmt.executeUpdate();
        }
    }

    private void eliminarPrestamo(Connection conn, String idStr) throws SQLException {
        String sql = "DELETE FROM prestamo WHERE id_prestamo = ?";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, Integer.parseInt(idStr));
            pstmt.executeUpdate();
        }
    }

    static class Prestamo {
        Integer id_prestamo;
        Integer id_estudiante;
        Integer id_libro;
        String fecha_prestamo;
        String fecha_limite;
        String fecha_devolucion;
        String estado;
    }
}
