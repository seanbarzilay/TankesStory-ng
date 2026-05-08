/*
    This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
                       Matthias Butz <matze@odinms.de>
                       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package server.bot;

import net.packet.OutPacket;

import java.awt.Point;

/**
 * Synthesizes the v83 MOVE_PLAYER movement-list byte stream that
 * {@link net.server.channel.handlers.AbstractMovementPacketHandler#parseMovement}
 * round-trips back to {@link server.movement.AbsoluteLifeMovement}.
 *
 * <p>v1.1 uses one absolute-move command per actuator tick. Bot smoothness is
 * a function of the tick rate, not multi-segment movement.
 */
public final class MoveBuilder {

    /** Default stance when the bot doesn't otherwise care: standing right-facing. */
    public static final int STANCE_STAND_RIGHT = 4;

    private MoveBuilder() {}

    /**
     * Writes a movement list with one absolute-move command (type 0) into {@code p}.
     * The bytes match the format produced by a real v83 client's movement packet,
     * mirroring {@link server.movement.AbsoluteLifeMovement#serialize(OutPacket)}
     * preceded by a single-element list count.
     *
     * @param wobble pixelsPerSecond velocity. Use a non-zero value (e.g. ±125, 0)
     *               so the v83 client renders the bot as walking rather than
     *               teleporting; (0, 0) is fine for stationary updates.
     */
    public static void serializeAbsoluteStep(OutPacket p, Point dst, Point wobble,
                                             int stance, int durationMs, int fh) {
        p.writeByte(1);              // count
        p.writeByte(0);              // command type: absolute
        p.writePos(dst);             // x, y
        p.writePos(wobble);          // xwobble, ywobble (pixelsPerSecond)
        p.writeShort(fh);            // foothold
        p.writeByte(stance);         // newstate
        p.writeShort(durationMs);    // duration
    }
}
