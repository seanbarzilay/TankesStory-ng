package server.bot;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BotIdAllocatorTest {

    @Test
    void firstIdIsNegativeOneMillion() {
        BotIdAllocator a = new BotIdAllocator();
        assertEquals(-1_000_000, a.next());
    }

    @Test
    void idsAreMonotonicallyDecreasing() {
        BotIdAllocator a = new BotIdAllocator();
        int first = a.next();
        int second = a.next();
        int third = a.next();
        assertTrue(second < first);
        assertTrue(third < second);
        assertEquals(first - 1, second);
        assertEquals(second - 1, third);
    }

    @Test
    void allocatorStartsFromGivenSeed() {
        BotIdAllocator a = new BotIdAllocator(-2_000_000);
        assertEquals(-2_000_000, a.next());
        assertEquals(-2_000_001, a.next());
    }
}
