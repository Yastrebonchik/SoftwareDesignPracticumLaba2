package ru.mipt.devops.notes;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class NotesApplicationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void clearNotes() {
        jdbc.update("DELETE FROM notes");
    }

    @Test
    void healthChecksTheDatabase() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void createsReadsAndDeletesANote() throws Exception {
        String response = mvc.perform(post("/notes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"First note\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.text").value("First note"))
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(response).get("id").asLong();
        mvc.perform(get("/notes/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("First note"));
        mvc.perform(get("/notes")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(delete("/notes/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/notes/" + id)).andExpect(status().isNotFound());
        mvc.perform(delete("/notes/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/notes")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void rejectsInvalidNotes() throws Exception {
        for (String body : new String[]{"{}", "{\"text\":\"   \"}",
                json.writeValueAsString(new NotesController.NewNote("x".repeat(1001)))}) {
            mvc.perform(post("/notes").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/notes")).andExpect(jsonPath("$.length()").value(0));
    }
}
