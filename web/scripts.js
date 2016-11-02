/**
 * Copyright (c) 2016 Threema GmbH
 *
 * Licensed under the Apache License, Version 2.0, <see LICENSE-APACHE file>
 * or the MIT license <see LICENSE-MIT file>, at your option. This file may not be
 * copied, modified, or distributed except according to those terms.
 */

// Note: NEVER use those exact keys in production!
const PUBLIC_KEY = '424280166304526b4a2874a2270d091071fcc5c98959f7d4718715626df26204';
const PRIVATE_KEY = '74d427ae6a95dedde68850e0ff9da952acf69e6e41436230f126fbd220e1faea';
const TRUSTED_KEY = '232385faea4c0fca2c867bfb7ca74f634178ee0bc13364ee738e02cd4318e839';
const HOST = 'saltyrtc.example.org';
const PORT = 8765;
const DC_LABEL = 'much-secure';


class TestClient {

    start() {
        const permanentKey = new saltyrtcClient.KeyStore(PUBLIC_KEY, PRIVATE_KEY);
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

        document.querySelector('#sendSignaling').onclick = () => alert('Not yet implemented');
        document.querySelector('#sendDc').onclick = this.sendDc.bind(this);
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
        this.pc = new RTCPeerConnection({
            iceServers: [{urls: ['stun:stun.services.mozilla.com']}],
        });

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
        });
    }

    setupIceCandidateHandling() {
        console.debug('Setting up ICE candidate handling...');
        this.pc.onicecandidate = (e) => {
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
                this.pc.addIceCandidate(candidateInit);
            }
        });
    }

    initiatorFlow() {
        // Register answer handler
        this.task.once('answer', (answer) => {
            console.debug('Set remote description');
            this.pc.setRemoteDescription(answer.data).then(() => {
                console.info('WebRTC initialization done.');
            });
        });

        // Create offer
        console.debug('Create offer');
        this.pc.createOffer().then((offer) => {
            console.debug('Set local description');
            this.pc.setLocalDescription(offer).then(() => {
                console.debug('Send offer to peer');
                this.task.sendOffer(offer);
            });
        });
    }

    setState(type, value) {
        document.querySelector('#' + type + 'State').innerHTML = value;
    }

    sendDc() {
        const input = document.querySelector('#chatText')
        const text = input.value;
        const bytes = stringToUtf8a(text);
        console.debug('Sending', bytes.length, 'bytes:', bytes);
        this.sdc.send(bytes);
        input.value = '';
    }

}


ready(() => {
    let testClient = new TestClient();

    console.info('For debugging purposes, the test client instance is exposed as `window.client`.');
    window.client = testClient;

    testClient.start();

});
