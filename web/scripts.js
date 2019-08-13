// noinspection JSUnusedGlobalSymbols
/**
 * Copyright (c) 2016-2019 Threema GmbH
 *
 * Licensed under the Apache License, Version 2.0, <see LICENSE-APACHE file>
 * or the MIT license <see LICENSE-MIT file>, at your option. This file may not be
 * copied, modified, or distributed except according to those terms.
 */

const LOG_LEVEL = 'info';

// Note: NEVER use those exact keys in production!
const PRIVATE_KEY = '74d427ae6a95dedde68850e0ff9da952acf69e6e41436230f126fbd220e1faea';
const SERVER_KEY = 'f77fe623b6977d470ac8c7bf7011c4ad08a1d126896795db9d2b4b7a49ae1045';
const TRUSTED_KEY = '232385faea4c0fca2c867bfb7ca74f634178ee0bc13364ee738e02cd4318e839';
const HOST = 'server.saltyrtc.org';
const PORT = 443;
const STUN_SERVER = 'stun.l.google.com:19302';
const TURN_SERVER = null;
const TURN_USER = null;
const TURN_PASS = null;
const DC_LABEL = 'much-secure';

class FlowControlledDataChannel {
    constructor(dc, lowWaterMark = 262144, highWaterMark = 1048576) {
        this.dc = dc;
        this.paused = false;
        this.ready = Promise.resolve();
        this.highWaterMark = highWaterMark;

        // Drain once ready
        this.dc.bufferedAmountLowThreshold = lowWaterMark;
        this.dc.onbufferedamountlow = () => {
            // Continue once low water mark has been reached
            if (this.paused) {
                console.debug(`Data channel ${this.dc.label} resumed @ ${this.dc.bufferedAmount}`);
                this.paused = false;
                this.resolve();
            }
        };
    }

    write(message) {
        // Throw if paused
        if (this.paused) {
            throw new Error('Unable to write, data channel is paused!');
        }

        // Try sending
        // Note: Technically we should be able to catch a TypeError in case the
        //       underlying buffer is full. However, there are other reasons
        //       that can result in a TypeError and no browser has implemented
        //       this properly so far. Thus, we use a well-tested high water
        //       mark instead and try to never fill the buffer completely.
        this.dc.send(message);

        // Pause once high water mark has been reached
        if (!this.paused && this.dc.bufferedAmount >= this.highWaterMark) {
            this.paused = true;
            this.ready = new Promise((resolve) => this.resolve = resolve);
            console.debug(`Data channel ${this.dc.label} paused @ ${this.dc.bufferedAmount}`);
        }
    }
}

class UnboundedFlowControlledDataChannel extends FlowControlledDataChannel {
    constructor(dc, lowWaterMark = 262144, highWaterMark = 1048576) {
        super(dc, lowWaterMark, highWaterMark);
        this.queue = this.ready;
    }

    write(message) {
        // Wait until ready, then write
        // Note: This very simple technique allows for ordered message
        //       queueing by using the event loop.
        this.queue = this.queue.then(async () => {
            await this.ready;
            super.write(message);
        });
    }
}

class TestClient {
    start() {
        // Get key store from private key
        // noinspection JSValidateTypes
        const keyStore = new saltyrtcClient.KeyStore(PRIVATE_KEY);

        // Create SaltyRTC tasks
        // Note: We create a 'v0' task for backwards compatibility with legacy
        //       demo versions.
        const tasks = [
            new saltyrtcTaskWebrtc.WebRTCTaskBuilder()
                .withLoggingLevel(LOG_LEVEL)
                .withVersion('v1')
                .build(),
            new saltyrtcTaskWebrtc.WebRTCTaskBuilder()
                .withLoggingLevel(LOG_LEVEL)
                .withVersion('v0')
                .build(),
        ];

        // Create SaltyRTC client
        // noinspection JSCheckFunctionSignatures
        this.client = new saltyrtcClient.SaltyRTCBuilder()
            .withLoggingLevel(LOG_LEVEL)
            .connectTo(HOST, PORT)
            .withKeyStore(keyStore)
            .withServerKey(SERVER_KEY)
            .withTrustedPeerKey(TRUSTED_KEY)
            .withPingInterval(30)
            .usingTasks(tasks)
            .asInitiator();
        this.client.on('state-change', this.onStateChange.bind(this));
        this.client.on('connection-error', this.onConnectionError.bind(this));
        this.client.on('connection-closed', this.onConnectionClosed.bind(this));
        this.client.connect();

        // Bind button click events
        document.querySelector('#sendSignaling').onclick = this.sendSignaling.bind(this);
        document.querySelector('#sendDc').onclick = this.sendDc.bind(this);
        document.querySelector('#sendData').onclick = this.sendData.bind(this);
    }

    onStateChange(newState) {
        console.debug('New state:', newState);
        this.setState('saltySignaling', newState.data);
        if (newState.data === 'task') {
            // Store chosen task
            this.task = this.client.getTask();

            // Enable signalling UI elements
            const messages = document.querySelector('#messages');
            messages.classList.remove('disabled');
            const loading = document.querySelector('#loading');
            loading.parentNode.removeChild(loading);

            // Initialise WebRTC peer-to-peer connection
            this.initWebRTC();
        }
    }

    onConnectionError(ev) {
        console.debug('Connection error:', ev);
    }

    onConnectionClosed(closeCode) {
        console.debug('Connection was closed with code', closeCode);
    }

    initWebRTC() {
        console.debug('Initialize WebRTC connection...');

        // Create RTC peer connection
        const iceServers = [{urls: [`stun:${STUN_SERVER}`]}];
        if (TURN_SERVER !== null) {
            iceServers.push({
                urls: [`turn:${TURN_SERVER}`],
                username: TURN_USER,
                credential: TURN_PASS,
                credentialType: 'password',
            })
        }
        this.pc = new RTCPeerConnection({iceServers: iceServers});

        // Let the "negotiationneeded" event trigger offer generation
        this.pc.onnegotiationneeded = () => {
            console.debug('Negotiation needed...');
            this.initiatorFlow().catch((e) => {
                console.error('Unable to send error:', e);
            });
        };

        // Handle state changes
        this.pc.onsignalingstatechange = () => {
            console.debug('RTC signaling state change:', this.pc.signalingState);
            this.setState('rtcSignaling', this.pc.signalingState);
        };
        this.pc.onconnectionstatechange = () => {
            console.debug('RTC connection state change:', this.pc.connectionState);
            this.setState('rtcConnection', this.pc.connectionState);
        };
        this.pc.oniceconnectionstatechange = () => {
            console.debug('ICE connection state change:', this.pc.iceConnectionState);
            this.setState('iceConnection', this.pc.iceConnectionState);
        };
        this.pc.onicegatheringstatechange = () => {
            console.debug('ICE gathering state change:', this.pc.iceGatheringState);
            this.setState('iceGathering', this.pc.iceGatheringState);
        };

        // Set up ICE candidate handling
        this.setupIceCandidateHandling();

        // Log incoming data channels
        this.pc.ondatachannel = (e) => {
            console.debug('New data channel was created:', e.channel.label);
        };

        // Create data channel for handover and initiate handover once open
        this.prepareHandover();

        // On handover, create the 'much-secure' data channel
        this.client.on('handover', () => {
            console.info('Handover done');
            this.setState('handover', 'yes');
            this.createMuchSecureChannel();
        });
    }

    setupIceCandidateHandling() {
        console.debug('Setting up ICE candidate handling...');
        this.pc.onicecandidate = (e) => {
            if (e.candidate) {
                console.debug('New local candidate:', e.candidate.candidate);
            } else {
                console.debug('New local candidate:', e.candidate);
            }
            if (e.candidate) {
                this.task.sendCandidate({
                    candidate: e.candidate.candidate,
                    sdpMid: e.candidate.sdpMid,
                    sdpMLineIndex: e.candidate.sdpMLineIndex,
                });
            }
            this.setState('iceGathering', this.pc.iceGatheringState);
        };
        this.pc.onicecandidateerror = (e) => console.error('ICE candidate error:', e);

        this.task.on('candidates', (e) => {
            for (const candidateInit of e.data) {
                console.debug('New remote candidate:', candidateInit.candidate);
                this.pc.addIceCandidate(candidateInit).catch((e) => {
                    console.error('Unable to add ICE candidate:', e);
                });
            }
        });
    }

    prepareHandover() {
        // Get transport link
        const link = this.task.getTransportLink();

        // Create data channel
        const dc = this.pc.createDataChannel(link.label, {
            id: link.id,
            negotiated: true,
            ordered: true,
            protocol: link.protocol,
        });
        dc.binaryType = 'arraybuffer';

        // Wrap as unbounded, flow-controlled data channel
        const ufcdc = new UnboundedFlowControlledDataChannel(dc);

        // Create transport handler
        const pc = this.pc;
        const handler = {
            get maxMessageSize() {
                return pc.sctp.maxMessageSize;
            },
            close() {
                console.debug(`Data channel ${dc.label} close request`);
                dc.close();
            },
            send(message) {
                console.debug(`Data channel ${dc.label} outgoing signaling message of ` +
                    `length ${message.byteLength}`);
                ufcdc.write(message);
            },
        };

        // Bind events
        dc.onopen = () => {
            console.info(`Data channel ${dc.label} open`);

            // Rebind close event
            dc.onclose = () => {
                console.info(`Data channel ${dc.label} closed`);
                link.closed();
            };

            // Initiate handover
            this.task.handover(handler);
        };
        dc.onclose = () => {
            console.error(`Data channel ${dc.label} closed`);
        };
        dc.onerror = (event) => {
            console.error(`Data channel ${dc.label} error:`, event);
        };
        dc.onmessage = (event) => {
            console.debug(`Data channel ${dc.label} incoming signaling message of ` +
                `length ${event.data.byteLength}`);
            link.receive(new Uint8Array(event.data));
        };
        
        // Attach to this for debug purposes
        // noinspection JSUnusedGlobalSymbols
        this.sdc = dc;
    }

    async initiatorFlow() {
        // Register answer handler
        this.task.once('answer', async (answer) => {
            console.warn('Answer', answer);
            console.debug('Set remote description');
            await this.pc.setRemoteDescription(answer.data);
            console.info('WebRTC initialization done.');
        });

        // Create offer
        console.debug('Create offer');
        const offer = await this.pc.createOffer();
        console.warn('Offer', offer);
        console.debug('Set local description');
        await this.pc.setLocalDescription(offer);
        console.debug('Send offer to peer');
        // noinspection JSCheckFunctionSignatures
        this.task.sendOffer(offer);
    }

    createMuchSecureChannel() {
        // Create channel
        const dc = this.pc.createDataChannel(DC_LABEL);
        dc.binaryType = 'arraybuffer';

        // Wrap as unbounded, flow-controlled data channel
        const ufcdc = new UnboundedFlowControlledDataChannel(dc);

        // Create crypto context
        // Note: We need to apply encrypt-then-chunk for backwards
        //       compatibility reasons.
        const crypto = this.task.getCryptoContext(dc.id);

        // Create unchunker
        // Note: We need to use an unreliable unordered unchunker for backwards
        //       compatibility reasons.
        const unchunker = new chunkedDc.UnreliableUnorderedUnchunker();

        // Bind events
        dc.onopen = () => {
            console.info(`Data channel ${dc.label} open`);
            this.setState('dataChannel', 'open');

            // Enable submit via data channel button
            this.enableDc();
        };
        dc.onclose = () => {
            console.info(`Data channel ${dc.label} closed`);
            this.setState('dataChannel', 'closed');
        };
        dc.onerror = (event) => {
            console.error(`Data channel ${dc.label} error:`, event);
            this.setState('dataChannel', dc.readyState);
        };
        dc.onmessage = (event) => {
            console.debug(`Data channel ${dc.label} incoming chunk ` +
                `of length ${event.data.byteLength}`);
            unchunker.add(new Uint8Array(event.data));
        };
        // noinspection JSUndefinedPropertyAssignment
        unchunker.onMessage = (array) => {
            const box = saltyrtcClient.Box.fromUint8Array(
                array, saltyrtcTaskWebrtc.DataChannelCryptoContext.NONCE_LENGTH);
            const message = crypto.decrypt(box);
            console.debug(`Data channel ${dc.label} incoming message ` +
                `of length ${message.byteLength}`);

            // Convert to string
            // TODO: This is ugly... we should use a separate channel instead
            let text;
            if (message.byteLength < 255) {
                text = new TextDecoder().decode(message);
            } else {
                text = `[${Math.trunc(message.byteLength / 1024)} KiB binary data]`;
            }

            // Display
            const messages = document.querySelector('textarea');
            messages.value += `< ${text}\n`;
            messages.scrollTop = messages.scrollHeight;
        };

        // Attach to this
        this.msdc = {
            dc: dc,
            ufcdc: ufcdc,
            crypto: crypto,
            messageId: 0,
        };
    }

    sendMuchSecureChannel(message) {
        console.debug(`Data channel ${this.msdc.dc.label} outgoing message ` +
            `of length ${message.byteLength}`);
        const box = this.msdc.crypto.encrypt(message);
        const chunkLength = Math.min(262144, this.pc.sctp.maxMessageSize);
        const chunker = new chunkedDc.UnreliableUnorderedChunker(
            this.msdc.messageId++, box.toUint8Array(), chunkLength);
        for (const chunk of chunker) {
            console.debug(`Data channel ${this.msdc.dc.label} outgoing chunk ` +
                `of length ${chunk.byteLength}`);
            this.msdc.ufcdc.write(chunk);
        }
    }

    enableDc() {
        document.querySelector('#sendDc').disabled = false;
    }

    setState(type, value) {
        document.querySelector(`#${type}State`).innerHTML = value;
    }

    sentMsg(text) {
        const input = document.querySelector('#chatText');
        const messages = document.querySelector('textarea');
        messages.value += `> ${text}\n`;
        // noinspection JSUndefinedPropertyAssignment
        input.value = '';
        messages.scrollTop = messages.scrollHeight;
    }

    sendSignaling() {
        const input = document.querySelector('#chatText');
        const text = input.value;
        const message = new TextEncoder().encode(text);
        console.debug('Sending', message.length, 'bytes through signaling channel:', message);
        this.client.sendApplicationMessage(message.buffer);
        this.sentMsg(text);
    }

    sendDc() {
        const input = document.querySelector('#chatText');
        const text = input.value;
        const message = new TextEncoder().encode(text);
        this.sendMuchSecureChannel(message);
        this.sentMsg(text);
    }

    sendData() {
        const length = document.querySelector('#binaryLength');
        const array = new Uint8Array(length.value * 1024);
        array.fill(0xee);
        this.sendMuchSecureChannel(array);
        this.sentMsg(`[${Math.trunc(array.byteLength / 1024)} KiB binary data]`);
    }
}


document.addEventListener('DOMContentLoaded', () => {
    let testClient = new TestClient();

    console.info('For debugging purposes, the test client instance is exposed as `window.client`.');
    window.client = testClient;

    testClient.start();
});
