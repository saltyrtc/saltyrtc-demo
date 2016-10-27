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
        console.log('New state:', newState);
        document.querySelector('#state').innerHTML = newState.data;
        if (newState.data == 'task') {
            const messages = document.querySelector('#messages');
            messages.classList.remove('disabled');
            const loading = document.querySelector('#loading');
            loading.parentNode.removeChild(loading);
        }
    }

}


ready(() => {
    let client = new TestClient();
    client.start();
});
