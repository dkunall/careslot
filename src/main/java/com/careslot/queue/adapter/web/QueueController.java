package com.careslot.queue.adapter.web;

import com.careslot.queue.application.QueueService;
import com.careslot.queue.application.QueueToken;
import com.careslot.scheduling.application.BookingService;
import com.careslot.shared.security.Actor;
import com.careslot.shared.security.Actors;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
class QueueController {

    record CompleteBody(@Size(max = 4000) String note) {
    }

    record TokenJson(UUID appointmentId, UUID practitionerId, LocalDate day, int number, String status) {
        static TokenJson of(QueueToken t) {
            return new TokenJson(t.appointmentId(), t.practitionerId(), t.day(), t.number(), t.status());
        }
    }

    private final QueueService queue;
    private final BookingService booking;

    QueueController(QueueService queue, BookingService booking) {
        this.queue = queue;
        this.booking = booking;
    }

    @PostMapping("/v1/appointments/{id}/check-in")
    ResponseEntity<TokenJson> checkIn(@PathVariable UUID id) {
        QueueService.CheckIn r = queue.checkIn(Actors.current(), id);
        return ResponseEntity.status(r.created() ? 201 : 200).body(TokenJson.of(r.token()));
    }

    @PostMapping("/v1/practitioners/{id}/queue/call-next")
    ResponseEntity<TokenJson> callNext(@PathVariable UUID id) {
        return queue.callNext(Actors.current(), id).map(t -> ResponseEntity.ok(TokenJson.of(t)))
                .orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/v1/appointments/{id}/complete")
    TokenJson complete(@PathVariable UUID id, @Valid @RequestBody(required = false) CompleteBody body) {
        return TokenJson.of(queue.complete(Actors.current(), id, body == null ? null : body.note()));
    }

    @GetMapping("/v1/practitioners/{id}/queue")
    QueueService.Board board(@PathVariable UUID id, @RequestParam LocalDate date) {
        return queue.board(Actors.current(), id, date);
    }

    @GetMapping("/v1/appointments/{id}/queue-eta")
    QueueService.Eta eta(@PathVariable UUID id) {
        Actor actor = Actors.current();
        UUID patient = booking.get(actor, id).patientId();
        return queue.eta(actor, id, patient);
    }
}
