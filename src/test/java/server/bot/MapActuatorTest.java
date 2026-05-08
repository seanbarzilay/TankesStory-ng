package server.bot;

import client.Character;
import config.BotConfig;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import testutil.Mocks;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

class MapActuatorTest {

    static Bot bot(int id) {
        Character chr = Mocks.chr("Bot01");
        when(chr.getId()).thenReturn(id);
        when(chr.getPosition()).thenReturn(new Point(0, 0));
        return new Bot(chr);
    }

    @Test
    void broadcastStepUpdatesPositionAndCallsBroadcast() {
        BotConfig cfg = new BotConfig();
        MapActuator a = new MapActuator(cfg);
        Bot b = bot(-1_000_000);
        MapleMap map = mock(MapleMap.class);
        when(b.character().getMap()).thenReturn(map);
        Point dst = new Point(50, 100);

        Packet pkt = a.broadcastStep(b, dst);
        assertNotNull(pkt);
        verify(b.character()).setPosition(eq(new Point(50, 100)));
        verify(map).broadcastMessage(same(b.character()), same(pkt), eq(false));
    }

    @Test
    void broadcastStepDoesNothingWhenMapIsNull() {
        MapActuator a = new MapActuator(new BotConfig());
        Bot b = bot(-1_000_000);
        when(b.character().getMap()).thenReturn(null);
        Packet pkt = a.broadcastStep(b, new Point(1, 1));
        assertNotNull(pkt, "still produces a packet, just doesn't broadcast");
    }

    @Test
    void stepTowardTargetMovesOneStepCloser() {
        Character target = Mocks.chr("Target");
        when(target.getPosition()).thenReturn(new Point(500, 0));
        MapActuator a = new MapActuator(new BotConfig(), id -> id == 999 ? target : null);
        Bot b = bot(-1_000_000);
        when(b.character().getPosition()).thenReturn(new Point(0, 0));
        MapleMap map = mock(MapleMap.class);
        when(b.character().getMap()).thenReturn(map);

        a.stepTowardTarget(b, 999);
        verify(map).broadcastMessage(same(b.character()), any(), eq(false));
    }

    @Test
    void stepTowardTargetUnknownIdIsNoOp() {
        MapActuator a = new MapActuator(new BotConfig(), id -> null);
        Bot b = bot(-1_000_000);
        MapleMap map = mock(MapleMap.class);
        when(b.character().getMap()).thenReturn(map);
        a.stepTowardTarget(b, 999);
        verify(map, never()).broadcastMessage(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void stepTowardMobMovesOneStepCloser() {
        MapActuator a = new MapActuator(new BotConfig());
        Bot b = bot(-1_000_000);
        when(b.character().getPosition()).thenReturn(new Point(0, 0));
        MapleMap map = mock(MapleMap.class);
        when(b.character().getMap()).thenReturn(map);
        server.life.Monster mob = mock(server.life.Monster.class);
        when(mob.getPosition()).thenReturn(new Point(300, 0));
        when(map.getMonsterByOid(42)).thenReturn(mob);
        a.stepTowardMob(b, 42);
        verify(map).broadcastMessage(same(b.character()), any(), eq(false));
    }

    @Test
    void retreatBroadcastsAStep() {
        MapActuator a = new MapActuator(new BotConfig());
        Bot b = bot(-1_000_000);
        when(b.character().getPosition()).thenReturn(new Point(0, 0));
        MapleMap map = mock(MapleMap.class);
        when(b.character().getMap()).thenReturn(map);
        a.retreatStep(b);
        verify(map).broadcastMessage(same(b.character()), any(), eq(false));
    }
}
