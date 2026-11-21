package lab.ticketmaster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import lab.ticketmaster.queue.WaitingRoom;

/**
 * Step 6: waiting room on, 2 people let in per tick. The automatic tick is set to 1 hour, so
 * the test lets people in by calling admitNext itself.
 */
@SpringBootTest(properties = {"queue.enabled=true", "queue.admit-per-second=2", "queue.tick=1h"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class WaitingRoomTest {

    @Autowired MockMvc mvc;
    @Autowired WaitingRoom room;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcClient db;

    @BeforeEach
    void reset() {
        db.sql("UPDATE tickets SET status = 'available', booking_id = NULL, reserved_until = NULL").update();
        db.sql("DELETE FROM bookings").update();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void cannotReserveWithoutQueueing() throws Exception {
        reserve("ana", 1).andExpect(status().isForbidden())
                .andExpect(r -> assertThat(r.getResolvedException().getMessage()).contains("not been let in"));
    }

    @Test
    void peopleGetPlacesInArrivalOrder() throws Exception {
        join("ana").andExpect(jsonPath("$.position").value(1));
        join("ben").andExpect(jsonPath("$.position").value(2));
        join("cara").andExpect(jsonPath("$.position").value(3))
                .andExpect(jsonPath("$.estimatedWaitSeconds").value(2 * 3600)); // 3rd, 2 per tick, tick = 1h
        join("ana").andExpect(jsonPath("$.position").value(1));        // joining again keeps your place
    }

    @Test
    void onlyAdmittedPeopleMayReserve() throws Exception {
        join("ana");
        join("ben");
        join("cara");

        room.admitNext(1); // one tick: the first 2 are let in

        where("ana").andExpect(jsonPath("$.admitted").value(true));
        where("cara").andExpect(jsonPath("$.admitted").value(false))
                .andExpect(jsonPath("$.position").value(1)); // moved up

        reserve("ana", 1).andExpect(status().isCreated());
        reserve("ben", 2).andExpect(status().isCreated());
        reserve("cara", 3).andExpect(status().isForbidden());

        room.admitNext(1);
        reserve("cara", 3).andExpect(status().isCreated());
    }

    @Test
    void admissionExpiresByItself() throws Exception {
        join("ana");
        room.admitNext(1);
        redis.delete("admitted:1:ana"); // what the TTL does after queue.admitted-for
        reserve("ana", 1).andExpect(status().isForbidden());
    }

    private ResultActions join(String user) throws Exception {
        return mvc.perform(post("/events/1/queue").header("X-User-Id", user)).andExpect(status().isOk());
    }

    private ResultActions where(String user) throws Exception {
        return mvc.perform(get("/events/1/queue").header("X-User-Id", user)).andExpect(status().isOk());
    }

    private ResultActions reserve(String user, long ticketId) throws Exception {
        return mvc.perform(post("/bookings/reserve")
                .header("X-User-Id", user)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\": 1, \"ticketIds\": [" + ticketId + "]}"));
    }
}
