package net.server.chat.irc;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

class QuestionTagTest {

    @Test
    void marker_formatsId() {
        assertEquals("[#42]", QuestionTag.marker(42));
        assertEquals("[#0]", QuestionTag.marker(0));
    }

    @Test
    void parseFirst_atStart_returnsId() {
        assertEquals(OptionalInt.of(42), QuestionTag.parseFirst("[#42] hello"));
    }

    @Test
    void parseFirst_inMiddle_returnsId() {
        assertEquals(OptionalInt.of(7), QuestionTag.parseFirst("hi [#7] there"));
    }

    @Test
    void parseFirst_noTag_returnsEmpty() {
        assertTrue(QuestionTag.parseFirst("hello").isEmpty());
        assertTrue(QuestionTag.parseFirst("").isEmpty());
        assertTrue(QuestionTag.parseFirst("[#abc]").isEmpty());
        assertTrue(QuestionTag.parseFirst("[42]").isEmpty());
    }

    @Test
    void parseFirst_multipleTags_returnsFirst() {
        assertEquals(OptionalInt.of(42), QuestionTag.parseFirst("[#42] [#99]"));
    }

    @Test
    void parseFirst_overflow_returnsEmpty() {
        assertTrue(QuestionTag.parseFirst("[#999999999999999999] hi").isEmpty());
    }

    @Test
    void parseFirst_null_returnsEmpty() {
        assertTrue(QuestionTag.parseFirst(null).isEmpty());
    }

    @Test
    void strip_atStartWithSpace_removesMarkerAndOneSpace() {
        assertEquals("hello", QuestionTag.strip("[#42] hello"));
    }

    @Test
    void strip_atStartNoSpace_removesOnlyMarker() {
        assertEquals("hello", QuestionTag.strip("[#42]hello"));
    }

    @Test
    void strip_inMiddle_collapsesAdjacentSpace() {
        assertEquals("hi there", QuestionTag.strip("hi [#42] there"));
    }

    @Test
    void strip_lonelyMarker_returnsEmpty() {
        assertEquals("", QuestionTag.strip("[#42]"));
    }

    @Test
    void strip_noTag_returnsAsIs() {
        assertEquals("hello", QuestionTag.strip("hello"));
    }
}
