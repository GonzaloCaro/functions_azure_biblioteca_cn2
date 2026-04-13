package com.functions.serverless;

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
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

public class EstudianteAzureHandler {

    private static final Gson GSON = new Gson();
    private static final AtomicBoolean WALLET_READY = new AtomicBoolean(false);

    @FunctionName("EstudianteCrudHandler")
    public HttpResponseMessage handleStudentOperations(
            @HttpTrigger(
                    name = "request",
                    methods = { HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE },
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "estudiantes")
            HttpRequestMessage<String> request,
            final ExecutionContext context) {

        try (Connection connection = openConnection()) {
            String httpMethod = request.getHttpMethod().name();

            switch (httpMethod.toUpperCase()) {
                case "GET":
                    List<Estudiante> estudiantes = obtenerEstudiantes(connection);
                    return jsonResponse(request, HttpStatus.OK, GSON.toJson(estudiantes));

                case "POST":
                    String bodyPost = request.getBody();
                    Estudiante nuevoEst = GSON.fromJson(bodyPost, Estudiante.class);
                    crearEstudiante(connection, nuevoEst);
                    return jsonResponse(request, HttpStatus.CREATED, "{\"mensaje\": \"Estudiante creado exitosamente\"}");

                case "PUT":
                    String bodyPut = request.getBody();
                    Estudiante estUpdate = GSON.fromJson(bodyPut, Estudiante.class);
                    actualizarEstudiante(connection, estUpdate);
                    return jsonResponse(request, HttpStatus.OK, "{\"mensaje\": \"Estudiante actualizado exitosamente\"}");

                case "DELETE":
                    String id = request.getQueryParameters().get("id");
                    if (id == null || id.isEmpty()) {
                        return jsonResponse(request, HttpStatus.BAD_REQUEST, "{\"error\": \"Se requiere parámetro id\"}");
                    }
                    eliminarEstudiante(connection, id);
                    return jsonResponse(request, HttpStatus.OK, "{\"mensaje\": \"Estudiante eliminado exitosamente\"}");

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

    private List<Estudiante> obtenerEstudiantes(Connection conn) throws SQLException {
        String sql = "SELECT id_estudiante, rut, nombre, apellido, email, telefono FROM estudiante ORDER BY id_estudiante ASC";
        List<Estudiante> estudiantes = new ArrayList<>();

        try (PreparedStatement pstmt = conn.prepareStatement(sql); ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                Estudiante estudiante = new Estudiante();
                estudiante.id_estudiante = rs.getInt("id_estudiante");
                estudiante.rut = rs.getString("rut");
                estudiante.nombre = rs.getString("nombre");
                estudiante.apellido = rs.getString("apellido");
                estudiante.email = rs.getString("email");
                estudiante.telefono = rs.getString("telefono");
                estudiantes.add(estudiante);
            }
        }
        return estudiantes;
    }

    private void crearEstudiante(Connection conn, Estudiante est) throws SQLException {
        String sql = "INSERT INTO estudiante (rut, nombre, apellido, email, telefono) VALUES (?, ?, ?, ?, ?)";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, est.rut);
            pstmt.setString(2, est.nombre);
            pstmt.setString(3, est.apellido);
            pstmt.setString(4, est.email);
            pstmt.setString(5, est.telefono);
            pstmt.executeUpdate();
        }
    }

    private void actualizarEstudiante(Connection conn, Estudiante est) throws SQLException {
        String sql = "UPDATE estudiante SET rut = ?, nombre = ?, apellido = ?, email = ?, telefono = ? WHERE id_estudiante = ?";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, est.rut);
            pstmt.setString(2, est.nombre);
            pstmt.setString(3, est.apellido);
            pstmt.setString(4, est.email);
            pstmt.setString(5, est.telefono);
            pstmt.setInt(6, est.id_estudiante);
            pstmt.executeUpdate();
        }
    }

    private void eliminarEstudiante(Connection conn, String idStr) throws SQLException {
        String sql = "DELETE FROM estudiante WHERE id_estudiante = ?";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, Integer.parseInt(idStr));
            pstmt.executeUpdate();
        }
    }

    static class Estudiante {
        Integer id_estudiante;
        String rut;
        String nombre;
        String apellido;
        String email;
        String telefono;
    }
}
