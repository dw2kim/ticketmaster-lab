package lab.ticketmaster;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Step 2: buyers one at a time. Everything here passes, and that is the point:
 * these tests cannot see the race. Step 3 adds buyers at the same moment.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class NaiveAppTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;

    @BeforeEach
    void resetSeats() {
        db.sql("UPDATE tickets SET status = 'available', booking_id = NULL").update();
        db.sql("DELETE FROM bookings").update();
    }

    @Test
    void viewEventShowsTwentyFreeSeats() throws Exception {
        mvc.perform(get("/events/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Lab Concert"))
                .andExpect(jsonPath("$.tickets.length()").value(20))
                .andExpect(jsonPath("$.tickets[0].status").value("available"));
    }

    @Test
    void unknownEventIs404() throws Exception {
        mvc.perform(get("/events/999")).andExpect(status().isNotFound());
    }

    @Test
    void searchFindsByKeyword() throws Exception {
        mvc.perform(get("/events/search").param("keyword", "jazz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Quiet Jazz Night"));
    }

    @Test
    void bookOneTicket() throws Exception {
        book("ana", 1).andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookingId").isNumber());

        mvc.perform(get("/events/1"))
                .andExpect(jsonPath("$.tickets[0].status").value("booked"));
    }

    @Test
    void secondBuyerOfSameSeatIsRejectedWhenTheyComeOneAtATime() throws Exception {
        book("ana", 1).andExpect(status().isCreated());
        book("ben", 1).andExpect(status().isConflict());
    }

    @Test
    void twentyBuyersOneAtATimeSellExactlyTwentyTickets() throws Exception {
        for (long seat = 1; seat <= 20; seat++) {
            book("buyer-" + seat, seat).andExpect(status().isCreated());
        }
        book("buyer-21", 1).andExpect(status().isConflict());

        long booked = db.sql("SELECT count(*) FROM tickets WHERE event_id = 1 AND status = 'booked'")
                .query(Long.class).single();
        long bookings = db.sql("SELECT count(*) FROM bookings").query(Long.class).single();
        assertThat(booked).isEqualTo(20);
        assertThat(bookings).isEqualTo(20);
    }

    private org.springframework.test.web.servlet.ResultActions book(String user, long ticketId)
            throws Exception {
        return mvc.perform(post("/bookings/1")
                .header("X-User-Id", user)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ticketIds\": [" + ticketId + "]}"));
    }
}
