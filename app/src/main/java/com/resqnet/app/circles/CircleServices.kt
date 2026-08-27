package com.resqnet.app.circles

import com.resqnet.app.mesh.MessageRouter
import com.resqnet.app.protocol.SafetyStatus

class CircleService(
    private val repository: CircleRepository,
    private val router: MessageRouter,
) {
    fun observeCircles() = repository.observeCircles()
    suspend fun create(name: String) = router.createCircle(name)
    suspend fun invite(circleId: String, nodeId: String) = router.inviteToCircle(circleId, nodeId)
    suspend fun accept(inviteId: String) = router.acceptCircleInvite(inviteId)
    suspend fun decline(inviteId: String) = router.declineCircleInvite(inviteId)
    suspend fun rename(circleId: String, name: String) = router.renameCircle(circleId, name)
    suspend fun removeMember(circleId: String, nodeId: String) = router.removeCircleMember(circleId, nodeId)
    suspend fun leave(circleId: String) = router.leaveCircle(circleId)
    suspend fun dissolve(circleId: String) = router.dissolveCircle(circleId)
}

class CircleMessageService(
    private val repository: CircleRepository,
    private val router: MessageRouter,
) {
    suspend fun send(circleId: String, text: String) = router.createCircleMessage(circleId, text)
    fun observeMessages(circleId: String) = repository.observeMessages(circleId)
    suspend fun messages(circleId: String) = repository.messages(circleId)
    suspend fun deliveryProgress(messageId: String) = router.circleDeliveryProgress(messageId)
}

class CircleStatusService(
    private val repository: CircleRepository,
    private val router: MessageRouter,
) {
    suspend fun update(circleId: String, status: SafetyStatus, note: String?) =
        router.updateCircleStatus(circleId, status, note)
    suspend fun effective(circleId: String, memberNodeId: String) =
        router.effectiveCircleStatus(circleId, memberNodeId)
    suspend fun history(circleId: String, memberNodeId: String) =
        repository.statusHistory(circleId, memberNodeId)
}
