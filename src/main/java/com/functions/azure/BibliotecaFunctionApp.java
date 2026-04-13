package com.functions.azure;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

public class BibliotecaFunctionApp {

    private static final Gson GSON = new Gson();
    private static final AtomicBoolean WALLET_READY = new AtomicBoolean(false);

    @FunctionName("restGetLibros")
    public HttpResponseMessage restGetLibros(
            @HttpTrigger(
                    name = "request",
                    methods = { HttpMethod.GET },
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "rest/libros")
            HttpRequestMessage<String> request,
            final ExecutionContext context) {

        try (Connection connection = openConnection()) {
            List<Libro> libros = obtenerLibros(connection);
            return jsonResponse(request, HttpStatus.OK, GSON.toJson(libros));
        } catch (Exception ex) {
            context.getLogger().severe("Error restGetLibros: " + ex.getMessage());
            return errorResponse(request, ex);
        }
    }

    @FunctionName("restGetEstudiantes")
    public HttpResponseMessage restGetEstudiantes(
            @HttpTrigger(
                    name = "request",
                    methods = { HttpMethod.GET },
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "rest/estudiantes")
            HttpRequestMessage<String> request,
            final ExecutionContext context) {

        try (Connection connection = openConnection()) {
            List<Estudiante> estudiantes = obtenerEstudiantes(connection);
            return jsonResponse(request, HttpStatus.OK, GSON.toJson(estudiantes));
        } catch (Exception ex) {
            context.getLogger().severe("Error restGetEstudiantes: " + ex.getMessage());
            return errorResponse(request, ex);
        }
    }

    @FunctionName("graphqlLibros")
    public HttpResponseMessage graphqlLibros(
            @HttpTrigger(
                    name = "request",
                    methods = { HttpMethod.POST },
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "graphql/libros")
            HttpRequestMessage<String> request,
            final ExecutionContext context) {

        try (Connection connection = openConnection()) {
            GraphQlPayload payload = parseGraphQlPayload(request.getBody());
            String query = payload.query.toLowerCase();

            if (query.contains("listalibros") || query.contains("libros")) {
                List<Libro> libros = obtenerLibros(connection);
                String body = "{\"data\":{\"libros\":" + GSON.toJson(libros) + "}}";
                return jsonResponse(request, HttpStatus.OK, body);
            }

            if (query.contains("crearlibro") || query.contains("addlibro")) {
                Libro nuevoLibro = GSON.fromJson(payload.variables, Libro.class);
                crearLibro(connection, nuevoLibro);
                return jsonResponse(request, HttpStatus.OK,
                        "{\"data\":{\"crearLibro\":\"Libro creado exitosamente\"}}");
            }

            return jsonResponse(request, HttpStatus.BAD_REQUEST,
                    "{\"error\":\"Operacion GraphQL no soportada para libros\"}");
        } catch (Exception ex) {
            context.getLogger().severe("Error graphqlLibros: " + ex.getMessage());
            return errorResponse(request, ex);
        }
    }

    @FunctionName("graphqlPrestamos")
    public HttpResponseMessage graphqlPrestamos(
            @HttpTrigger(
                    name = "request",
                    methods = { HttpMethod.POST },
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "graphql/prestamos")
            HttpRequestMessage<String> request,
            final ExecutionContext context) {

        try (Connection connection = openConnection()) {
            GraphQlPayload payload = parseGraphQlPayload(request.getBody());
            String query = payload.query.toLowerCase();

            if (query.contains("listaprestamos") || query.contains("prestamos")) {
                List<Prestamo> prestamos = obtenerPrestamos(connection);
                String body = "{\"data\":{\"prestamos\":" + GSON.toJson(prestamos) + "}}";
                return jsonResponse(request, HttpStatus.OK, body);
            }

            if (query.contains("devolverprestamo")) {
                Prestamo prestamo = GSON.fromJson(payload.variables, Prestamo.class);
                devolverPrestamo(connection, prestamo.id_prestamo);
                return jsonResponse(request, HttpStatus.OK,
                        "{\"data\":{\"devolverPrestamo\":\"Prestamo actualizado exitosamente\"}}");
            }

            return jsonResponse(request, HttpStatus.BAD_REQUEST,
                    "{\"error\":\"Operacion GraphQL no soportada para prestamos\"}");
        } catch (Exception ex) {
            context.getLogger().severe("Error graphqlPrestamos: " + ex.getMessage());
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
                .body("{\"error\":\"" + escaped + "\"}")
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

    private GraphQlPayload parseGraphQlPayload(String body) {
        if (body == null || body.isBlank()) {
            return new GraphQlPayload("", "{}");
        }

        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        String query = json.has("query") ? json.get("query").getAsString() : "";
        String variables = json.has("variables") ? json.get("variables").toString() : "{}";
        return new GraphQlPayload(query, variables);
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

    private void devolverPrestamo(Connection conn, Integer idPrestamo) throws SQLException {
        if (idPrestamo == null) {
            throw new IllegalArgumentException("El campo id_prestamo es obligatorio");
        }

        String sql = "UPDATE prestamo SET estado = 'DEVUELTO', fecha_devolucion = SYSDATE WHERE id_prestamo = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, idPrestamo);
            pstmt.executeUpdate();
        }
    }

    static class GraphQlPayload {
        final String query;
        final String variables;

        GraphQlPayload(String query, String variables) {
            this.query = query;
            this.variables = variables;
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

    static class Estudiante {
        Integer id_estudiante;
        String rut;
        String nombre;
        String apellido;
        String email;
        String telefono;
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
