package lab.ticketmaster.booking;

import java.util.List;

/**
 * Books tickets and returns the new booking id.
 *
 * Step 4 has four implementations. Pick one at startup with booking.strategy:
 *   naive        - step 2, look then write, no protection (oversells)
 *   row-lock     - SELECT ... FOR UPDATE inside a short transaction
 *   occ          - optimistic: UPDATE ... WHERE version = ? (0 rows means someone beat you)
 *   conditional  - one UPDATE ... WHERE status = 'available' (the database checks and writes)
 */
public interface BookingService {

    long book(long eventId, String userId, List<Long> ticketIds);
}
