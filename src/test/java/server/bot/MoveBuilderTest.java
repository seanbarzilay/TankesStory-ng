package server.bot;

import client.Client;
import net.packet.InPacket;
import net.server.channel.handlers.AbstractMovementPacketHandler;
import org.junit.jupiter.api.Test;
import server.movement.AbsoluteLifeMovement;
import server.movement.LifeMovementFragment;
import testutil.Packets;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MoveBuilderTest {

    @Test
    void absoluteStepRoundTripsThroughParser() throws Exception {
        Point dst = new Point(100, -200);
        Point wobble = new Point(125, 0);
        int stance = 3;
        int durationMs = 200;
        int fh = 7;

        InPacket ip = Packets.buildInPacket(op ->
                MoveBuilder.serializeAbsoluteStep(op, dst, wobble, stance, durationMs, fh));

        TestHandler handler = new TestHandler();
        List<LifeMovementFragment> parsed = handler.callParse(ip);

        assertEquals(1, parsed.size());
        AbsoluteLifeMovement alm = (AbsoluteLifeMovement) parsed.get(0);
        assertEquals(0, alm.getType());
        assertEquals(dst, alm.getPosition());
        assertEquals(stance, alm.getNewstate());
        assertEquals(durationMs, alm.getDuration());
        assertEquals(fh, alm.getFh());
        assertEquals(wobble, alm.getPixelsPerSecond());
    }

    private static class TestHandler extends AbstractMovementPacketHandler {
        @Override
        public void handlePacket(InPacket p, Client c) { /* unused */ }

        List<LifeMovementFragment> callParse(InPacket p) throws Exception {
            return parseMovement(p);
        }
    }
}
