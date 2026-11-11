// Plays /player.html?video={id}. State is exposed on window.player so the browser test asserts on it
// instead of scraping the DOM.
(function () {
    var player = {
        supported: false,
        state: 'loading',   // loading | ready | playing | error
        levels: 0,
        fatalError: null,
        currentTime: 0
    };
    window.player = player;

    var message = document.getElementById('message');
    var video = document.getElementById('video');

    function fail(text) {
        player.state = 'error';
        player.fatalError = text;
        message.textContent = text;
    }

    var videoId = new URLSearchParams(window.location.search).get('video');
    if (!videoId) {
        fail('Missing ?video={id}');
        return;
    }

    video.addEventListener('timeupdate', function () {
        player.currentTime = video.currentTime;
        if (player.state === 'ready' && video.currentTime > 0) {
            player.state = 'playing';
        }
    });

    fetch('/api/videos/' + encodeURIComponent(videoId))
        .then(function (response) {
            if (!response.ok) {
                throw new Error('GET /api/videos/' + videoId + ' returned ' + response.status);
            }
            return response.json();
        })
        .then(function (dto) {
            if (!dto.playbackUrl) {
                throw new Error('Video ' + videoId + ' is not ready (status ' + dto.status + ')');
            }
            player.supported = Hls.isSupported();
            if (!player.supported) {
                throw new Error('hls.js is not supported in this browser');
            }
            var hls = new Hls();
            hls.on(Hls.Events.MANIFEST_PARSED, function (event, data) {
                player.levels = data.levels.length;
                player.state = 'ready';
                // Muted, so the browser allows autoplay.
                video.play().catch(function (e) { fail('play() rejected: ' + e.message); });
            });
            hls.on(Hls.Events.ERROR, function (event, data) {
                if (data.fatal) {
                    fail(data.type + ': ' + data.details);
                    hls.destroy();
                }
            });
            hls.loadSource(dto.playbackUrl);
            hls.attachMedia(video);
        })
        .catch(function (e) { fail(e.message); });
})();
