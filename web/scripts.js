/**
 * Copyright (c) 2016-2017 Threema GmbH
 *
 * Licensed under the Apache License, Version 2.0, <see LICENSE-APACHE file>
 * or the MIT license <see LICENSE-MIT file>, at your option. This file may not be
 * copied, modified, or distributed except according to those terms.
 */

// Note: NEVER use those exact keys in production!
const PUBLIC_KEY = '424280166304526b4a2874a2270d091071fcc5c98959f7d4718715626df26204';
const PRIVATE_KEY = '74d427ae6a95dedde68850e0ff9da952acf69e6e41436230f126fbd220e1faea';
const TRUSTED_KEY = '232385faea4c0fca2c867bfb7ca74f634178ee0bc13364ee738e02cd4318e839';
const HOST = 'server.saltyrtc.org';
const PORT = 9287;
const STUN_SERVER = 'stun.services.mozilla.com';
const TURN_SERVER = null;
const TURN_USER = null;
const TURN_PASS = null;
const DC_LABEL = 'much-secure';


class TestClient {

    start() {
        const permanentKey = new saltyrtcClient.KeyStore(PRIVATE_KEY);
        this.task = new saltyrtcTaskWebrtc.WebRTCTask();
        this.client = new saltyrtcClient.SaltyRTCBuilder()
            .connectTo(HOST, PORT)
            .withKeyStore(permanentKey)
            .withTrustedPeerKey(TRUSTED_KEY)
            .withPingInterval(30)
            .usingTasks([this.task])
            .asInitiator();
        this.client.on('state-change', this.onStateChange.bind(this));
        this.client.on('connection-error', this.onConnectionError.bind(this));
        this.client.on('connection-closed', this.onConnectionClosed.bind(this));
        this.client.connect();

        document.querySelector('#sendSignaling').onclick = this.sendSignaling.bind(this);
        document.querySelector('#sendDc').onclick = this.sendDc.bind(this);
        document.querySelector('#sendData').onclick = this.sendData.bind(this);
    }

    onStateChange(newState) {
        console.debug('New state:', newState);
        this.setState('saltySignaling', newState.data);
        if (newState.data == 'task') {
            const messages = document.querySelector('#messages');
            messages.classList.remove('disabled');
            const loading = document.querySelector('#loading');
            loading.parentNode.removeChild(loading);
            this.initWebrtc();
        }
    }

    onConnectionError(ev) {
        console.debug('Connection error:', ev);
    }

    onConnectionClosed(closeCode) {
        console.debug('Connection was closed with code', closeCode);
    }

    initWebrtc() {
        console.debug('Initialize WebRTC connection...');

        // Create RTC peer connection
        let iceServers = [{urls: ['stun:' + STUN_SERVER]}];
        if (TURN_SERVER !== null) {
            iceServers.push({
                urls: ['turn:' + TURN_SERVER],
                username: TURN_USER,
                credential: TURN_PASS,
                credentialType: 'password',
            })
        }
        this.pc = new RTCPeerConnection({iceServers: iceServers});

        // Let the "negotiationneeded" event trigger offer generation
        this.pc.onnegotiationneeded = (e) => {
            console.debug('Negotiation needed...');
            this.initiatorFlow();
        };

        // Handle state changes
        this.pc.onsignalingstatechange = (e) => {
            console.debug('RTC signaling state change:', this.pc.signalingState);
            this.setState('rtcSignaling', this.pc.signalingState);
        };
        this.pc.onconnectionstatechange = (e) => {
            console.debug('RTC connection state change:', e); // TODO: Does `e` contain the information?
            this.setState('rtcConnection', this.pc.connectionState);
        };
        this.pc.oniceconnectionstatechange = (e) => {
            console.debug('ICE connection state change:', this.pc.iceConnectionState);
            this.setState('iceConnection', this.pc.iceConnectionState);
        };
        this.pc.onicegatheringstatechange = (e) => {
            // TODO: This doesn't currently seem to be called by Chromium / Firefox
            console.debug('ICE gathering state change:', this.pc.iceGatheringState);
            this.setState('iceGathering', this.pc.iceGatheringState);
        }

        // Set up ICE candidate handling
        this.setupIceCandidateHandling();

        // Log incoming data channels
        this.pc.ondatachannel = (e) => {
            console.debug('New data channel was created:', e.channel.label);
        }

        // Request handover
        this.task.handover(this.pc);

        // On handover, wrap a new data channel
        this.client.on('handover', () => {
            console.info('Handover done');
            this.setState('handover', 'yes');

            const dc = this.pc.createDataChannel(DC_LABEL);
            dc.binaryType = 'arraybuffer';
            this.sdc = this.task.wrapDataChannel(dc);
            this.sdc.onopen = () => {
                console.info('Custom secure data channel is open');
                this.setState('dataChannel', this.sdc.readyState);
                setInterval(() => {
                    if (this.sdc != null) {
                        this.setState('dataChannel', this.sdc.readyState);
                    }
                }, 1000);
            };
            this.sdc.onerror = (e) => {
                console.error('Secure data channel error:', e);
                this.setState('dataChannel', this.sdc.readyState);
            };
            this.sdc.onclose = () => {
                console.error('Secure data channel was closed');
                this.setState('dataChannel', this.sdc.readyState);
            };
            this.sdc.onmessage = (ev) => {
                const messages = document.querySelector('textarea')
                const bytes = new Uint8Array(ev.data);
                const text = utf8aToString(bytes);
                console.log(text);
                console.debug('New incoming message:', bytes.length, 'bytes');
                messages.value += '< ' + text + '\n';
                messages.scrollTop = messages.scrollHeight;
            };

            // Enable "Submit via DataChannel" button
            this.enableDc();
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
        }
        this.pc.onicecandidateerror = (e) => console.error('ICE candidate error:', e);

        this.task.on('candidates', (e) => {
            for (let candidateInit of e.data) {
                console.debug('New remote candidate:', candidateInit.candidate);
                this.pc.addIceCandidate(candidateInit);
            }
        });
    }

    initiatorFlow() {
        // Register answer handler
        this.task.once('answer', (answer) => {
            console.warn('Answer', answer);
            console.debug('Set remote description');
            this.pc.setRemoteDescription(answer.data).then(() => {
                console.info('WebRTC initialization done.');
            });
        });

        // Create offer
        console.debug('Create offer');
        this.pc.createOffer().then((offer) => {
            console.warn('Offer', offer);
            console.debug('Set local description');
            this.pc.setLocalDescription(offer).then(() => {
                console.debug('Send offer to peer');
                this.task.sendOffer(offer);
            });
        });
    }

    enableDc() {
        document.querySelector('#sendDc').disabled = false;
    }

    setState(type, value) {
        document.querySelector('#' + type + 'State').innerHTML = value;
    }

    sentMsg(text) {
        const input = document.querySelector('#chatText')
        const messages = document.querySelector('textarea')
        messages.value += '> ' + text + '\n';
        input.value = '';
        messages.scrollTop = messages.scrollHeight;
    }

    sendSignaling() {
        const input = document.querySelector('#chatText')
        const text = input.value;
        const bytes = stringToUtf8a(text);
        console.debug('Sending', bytes.length, 'bytes through signaling channel:', bytes);
        this.client.sendApplicationMessage(bytes.buffer);
        this.sentMsg(text);
    }

    sendDc() {
        const input = document.querySelector('#chatText')
        const text = input.value;
        const bytes = stringToUtf8a(text);
        console.debug('Sending', bytes.length, 'bytes through data channel:', bytes);
        this.sdc.send(bytes);
        this.sentMsg(text);
    }

    sendData() {
        const data = new Uint8Array(600 * 1024);
        for (let i = 0; i < 600; i++) {
            data[i] = Math.random() * 250;
        }
        this.sdc.send(data);
        this.sentMsg("[sent 600 KiB random data]");
    }

}


ready(() => {
    let testClient = new TestClient();

    console.info('For debugging purposes, the test client instance is exposed as `window.client`.');
    window.client = testClient;

    testClient.start();

});
