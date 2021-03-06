/*-
 * ========================LICENSE_START=================================
 * idscp2
 * %%
 * Copyright (C) 2021 Fraunhofer AISEC
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */
package de.fhg.aisec.ids.idscp2.default_drivers.rat.dummy

import de.fhg.aisec.ids.idscp2.idscp_core.drivers.RatProverDriver
import de.fhg.aisec.ids.idscp2.idscp_core.fsm.InternalControlMessage
import de.fhg.aisec.ids.idscp2.idscp_core.fsm.fsmListeners.RatProverFsmListener
import org.slf4j.LoggerFactory
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

/**
 * A RatProver dummy that exchanges rat messages with a remote RatVerifier
 *
 * @author Leon Beckmann (leon.beckmann@aisec.fraunhofer.de)
 */
class RatProverDummy(fsmListener: RatProverFsmListener) : RatProverDriver<Unit>(fsmListener) {
    private val queue: BlockingQueue<ByteArray> = LinkedBlockingQueue()
    override fun delegate(message: ByteArray) {
        queue.add(message)
        if (LOG.isDebugEnabled) {
            LOG.debug("Delegated to prover")
        }
    }

    override fun run() {
        var countDown = 2
        while (running) {
            try {
                sleep(1000)
                fsmListener.onRatProverMessage(
                    InternalControlMessage.RAT_PROVER_MSG,
                    "test".toByteArray()
                )
                if (LOG.isDebugEnabled) {
                    LOG.debug("Prover waits")
                }
                queue.take()
                if (LOG.isDebugEnabled) {
                    LOG.debug("Prover receives, send something")
                }
                if (--countDown == 0) break
            } catch (e: InterruptedException) {
                if (running) {
                    fsmListener.onRatProverMessage(InternalControlMessage.RAT_PROVER_FAILED)
                }
                return
            }
        }
        fsmListener.onRatProverMessage(InternalControlMessage.RAT_PROVER_OK)
    }

    companion object {
        const val RAT_PROVER_DUMMY_ID = "Dummy"
        private val LOG = LoggerFactory.getLogger(RatProverDummy::class.java)
    }
}
