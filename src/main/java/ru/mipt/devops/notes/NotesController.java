package ru.mipt.devops.notes;

import java.net.URI;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class NotesController {
    private final JdbcTemplate jdbc;

    public NotesController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(Map.of("status", "UP"));
        } catch (DataAccessException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "DOWN"));
        }
    }

    @GetMapping("/notes")
    public List<Note> list() {
        return jdbc.query("SELECT id, text FROM notes ORDER BY id",
                (row, index) -> new Note(row.getLong("id"), row.getString("text")));
    }

    @GetMapping("/notes/{id}")
    public Note get(@PathVariable long id) {
        return jdbc.query("SELECT id, text FROM notes WHERE id = ?",
                        (row, index) -> new Note(row.getLong("id"), row.getString("text")), id)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping("/notes")
    public ResponseEntity<Note> create(@Valid @RequestBody NewNote request) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO notes (text) VALUES (?)", new String[]{"id"});
            statement.setString(1, request.text());
            return statement;
        }, key);
        long id = Objects.requireNonNull(key.getKey()).longValue();
        return ResponseEntity.created(URI.create("/notes/" + id))
                .body(new Note(id, request.text()));
    }

    @DeleteMapping("/notes/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        if (jdbc.update("DELETE FROM notes WHERE id = ?", id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.noContent().build();
    }

    public record NewNote(@NotBlank @Size(max = 1000) String text) {
    }
}
