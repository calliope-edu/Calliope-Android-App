// Captures the file name of blob downloads (anchor clicks and
// createElement('a')) and forwards MakeCode controller-mode downloads to the
// app. Injected into editors that download through blob URLs. Idempotent.
if (!window.androidDownloadInterceptAdded) {
    window.androidDownloadInterceptAdded = true;
    window.androidLastDownloadName = null;
    var originalClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function() {
        if (this.download && this.href && this.href.startsWith('blob:')) {
            window.androidLastDownloadName = this.download;
        }
        return originalClick.apply(this, arguments);
    };
    var originalCreateElement = document.createElement.bind(document);
    document.createElement = function(tag) {
        var el = originalCreateElement(tag);
        if (tag.toLowerCase() === 'a') {
            var desc = Object.getOwnPropertyDescriptor(HTMLAnchorElement.prototype, 'download');
            Object.defineProperty(el, 'download', {
                set: function(val) { window.androidLastDownloadName = val; desc.set.call(this, val); },
                get: function() { return desc.get.call(this); }
            });
        }
        return el;
    };
    window.addEventListener('message', function(ev) {
        var msg = ev.data;
        if (msg && msg.download && msg.name) {
            Android.handleControllerDownload(msg.download, msg.name);
        }
    }, false);
}
