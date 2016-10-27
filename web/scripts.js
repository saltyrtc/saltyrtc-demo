const PUBLIC_KEY = '424280166304526b4a2874a2270d091071fcc5c98959f7d4718715626df26204';
const PRIVATE_KEY = '74d427ae6a95dedde68850e0ff9da952acf69e6e41436230f126fbd220e1faea';
const TRUSTED_KEY = '232385faea4c0fca2c867bfb7ca74f634178ee0bc13364ee738e02cd4318e839';
const HOST = 'saltyrtc.threema.ch';
const PORT = 443;


class TestClient {

    start() {
		const pubKey = hexToU8a(PUBLIC_KEY);
		const privKey = hexToU8a(PRIVATE_KEY);
        const permanentKey = new saltyrtcClient.KeyStore(pubKey, privKey);
        this.task = new saltyrtcTaskWebrtc.WebRTCTask();
        this.client = new saltyrtcClient.SaltyRTCBuilder()
            .connectTo(HOST, PORT)
            .withKeyStore(permanentKey)
            .withTrustedPeerKey(hexToU8a(TRUSTED_KEY))
            .usingTasks([this.task])
            .asInitiator();
        this.client.on('state-change', this.onStateChange.bind(this));
        this.client.connect();
    }

    onStateChange(newState) {
        console.debug('New state:', newState);
        document.querySelector('#state').innerHTML = newState.data;
        if (newState.data == 'task') {
            const messages = document.querySelector('#messages');
            messages.classList.remove('disabled');
            const loading = document.querySelector('#loading');
            loading.parentNode.removeChild(loading);
            this.initWebrtc();
        }
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
        this.pc.onsignalingstatechange = (e) => console.debug('RTC signaling state change:', e); // TODO: Does `e` contain the information?
        this.pc.onconnectionstatechange = (e) => console.debug('RTC connection state change:', e); // TODO: Does `e` contain the information?
        this.pc.oniceconnectionstatechange = (e) => console.debug('ICE connection state change:', this.pc.iceConnectionState);
        this.pc.onicegatheringstatechange = (e) => console.debug('ICE gathering state change:', this.pc.iceGatheringState);

        // Set up ICE candidate handling
        this.setupIceCandidateHandling();

        // Log incoming data channels
        this.pc.ondatachannel = (e) => {
            console.debug('New data channel was created:', e.channel.label);
        }

        // Request handover
        this.task.handover(this.pc);
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

}


ready(() => {
    let client = new TestClient();
    client.start();
});
