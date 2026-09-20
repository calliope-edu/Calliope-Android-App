// Reads a blob: URL the page just offered for download and hands it to the
// app as a data URL. The app substitutes the three placeholders (as escaped
// JS string contents) before evaluating.
(function () {
    var xhr = new XMLHttpRequest();
    xhr.open('GET', '__BLOB_URL__', true);
    xhr.setRequestHeader('Content-type', '__MIME_TYPE__;charset=UTF-8');
    xhr.responseType = 'blob';
    xhr.onload = function (e) {
        if (this.status == 200) {
            var blobFile = this.response;
            var name = window.androidLastDownloadName;
            if (name) {
                name = name.replace(/\.hex$/i, '').replace(/^mini-/i, '');
            } else {
                name = blobFile.name;
            }
            if (!name) {
                name = '__FALLBACK_NAME__';
            }
            window.androidLastDownloadName = null;
            var reader = new FileReader();
            reader.readAsDataURL(blobFile);
            reader.onloadend = function () {
                Android.getBase64FromBlobData(reader.result, name);
            };
        }
    };
    xhr.send();
})();
