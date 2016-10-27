const PUBLIC_KEY = '424280166304526b4a2874a2270d091071fcc5c98959f7d4718715626df26204';
const PRIVATE_KEY = '74d427ae6a95dedde68850e0ff9da952acf69e6e41436230f126fbd220e1faea';
const TRUSTED_KEY = '232385faea4c0fca2c867bfb7ca74f634178ee0bc13364ee738e02cd4318e839';
const HOST = 'saltyrtc.threema.ch';
const PORT = 443;


function hexToU8a(hexstring) {
    let array, i, j, k, ref;
    j = 0;
    if (hexstring.length % 2 == 1) {
        hexstring = '0' + hexstring;
    }
    array = new Uint8Array(hexstring.length / 2);
    for (i = k = 0, ref = hexstring.length; k <= ref; i = k += 2) {
        array[j++] = parseInt(hexstring.substr(i, 2), 16);
    }
    return array;
}


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
        this.client.connect();
    }

}


let client = new TestClient();
client.start();
