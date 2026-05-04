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
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

public class LibroAzureHandler {

    private static final Gson GSON = new Gson();
    private static final AtomicBoolean WALLET_READY = new AtomicBoolean(false);

    @FunctionName("LibroCrudHandler")
    public HttpResponseMessage handleBookOperations(
            @HttpTrigger(
                    name = "request",
                    methods = { HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE },
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "libros")
            HttpRequestMessage<String> request,
            final ExecutionContext context) {

        try (Connection connection = openConnection()) {
            String httpMethod = request.getHttpMethod().name();

            switch (httpMethod.toUpperCase()) {
                case "GET":
                    List<Libro> libros = obtenerLibros(connection);
                    return jsonResponse(request, HttpStatus.OK, GSON.toJson(libros));

                case "POST":
                    String bodyPost = request.getBody();
                    Libro nuevoLibro = GSON.fromJson(bodyPost, Libro.class);
                    crearLibro(connection, nuevoLibro);
                    EventGridService.publicarEvento(
                            "/biblioteca/libros/" + nuevoLibro.isbn,
                            "Biblioteca.Libro.Creado",
                            nuevoLibro,
                            context.getLogger());
                    return jsonResponse(request, HttpStatus.CREATED, "{\"mensaje\": \"Libro creado exitosamente\"}");

                case "PUT":
                    String bodyPut = request.getBody();
                    Libro libroUpdate = GSON.fromJson(bodyPut, Libro.class);
                    actualizarLibro(connection, libroUpdate);
                    return jsonResponse(request, HttpStatus.OK, "{\"mensaje\": \"Libro actualizado exitosamente\"}");

                case "DELETE":
                    String id = request.getQueryParameters().get("id");
                    if (id == null || id.isEmpty()) {
                        return jsonResponse(request, HttpStatus.BAD_REQUEST, "{\"error\": \"Se requiere parámetro id\"}");
                    }
                    eliminarLibro(connection, id);
                    return jsonResponse(request, HttpStatus.OK, "{\"mensaje\": \"Libro eliminado exitosamente\"}");

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

    private List<Libro> obtenerLibros(Connection conn) throws SQLException {
        String sql = "SELECT id_libro, titulo, autor, isbn, anio_publicacion, stock FROM libro ORDER BY id_libro ASC";
        List<Libro> libros = new ArrayList<>();

        try (PreparedStatement pstmt = conn.prepareStatement(sql); ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                Libro libro = new Libro();
                libro.id_libro = rs.getInt("id_libro");
                libro.titulo = rs.getString("titulo");
                libro.autor = rs.getString("autor");
                libro.isbn = rs.getString("isbn");
                libro.anio_publicacion = readNullableInteger(rs, "anio_publicacion");
                libro.stock = readNullableInteger(rs, "stock");
                libros.add(libro);
            }
        }
        return libros;
    }

    private Integer readNullableInteger(ResultSet rs, String columnName) throws SQLException {
        Object raw = rs.getObject(columnName);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number) {
            return ((Number) raw).intValue();
        }
        return Integer.valueOf(raw.toString());
    }

    private void crearLibro(Connection conn, Libro libro) throws SQLException {
        String sql = "INSERT INTO libro (titulo, autor, isbn, anio_publicacion, stock) VALUES (?, ?, ?, ?, ?)";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, libro.titulo);
            pstmt.setString(2, libro.autor);
            pstmt.setString(3, libro.isbn);
            if (libro.anio_publicacion != null) {
                pstmt.setInt(4, libro.anio_publicacion);
            } else {
                pstmt.setNull(4, Types.INTEGER);
            }
            if (libro.stock != null) {
                pstmt.setInt(5, libro.stock);
            } else {
                pstmt.setInt(5, 1);
            }
            pstmt.executeUpdate();
        }
    }

    private void actualizarLibro(Connection conn, Libro libro) throws SQLException {
        String sql = "UPDATE libro SET titulo = ?, autor = ?, isbn = ?, anio_publicacion = ?, stock = ? WHERE id_libro = ?";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, libro.titulo);
            pstmt.setString(2, libro.autor);
            pstmt.setString(3, libro.isbn);
            pstmt.setInt(4, libro.anio_publicacion);
            pstmt.setInt(5, libro.stock);
            pstmt.setInt(6, libro.id_libro);
            pstmt.executeUpdate();
        }
    }

    private void eliminarLibro(Connection conn, String idStr) throws SQLException {
        String sql = "DELETE FROM libro WHERE id_libro = ?";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, Integer.parseInt(idStr));
            pstmt.executeUpdate();
        }
    }

    static class Libro {
        Integer id_libro;
        String titulo;
        String autor;
        String isbn;
        Integer anio_publicacion;
        Integer stock;
    }
}
