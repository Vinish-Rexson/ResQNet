package com.resqnet.app.mesh.barp

import com.resqnet.app.protocol.CircleStatusBody
import com.resqnet.app.protocol.PacketKind
import com.resqnet.app.protocol.PayloadV2
import com.resqnet.app.protocol.SafetyStatus

/**
 * BARP's critical class is deliberately narrow until a signed SOS packet exists.
 * Add PacketKind.SOS_ALERT here when that packet kind is introduced.
 */
fun PayloadV2.isBarpCriticalTraffic(): Boolean =
    kind == PacketKind.CIRCLE_STATUS &&
        (body as? CircleStatusBody)?.status == SafetyStatus.NEED_HELP
