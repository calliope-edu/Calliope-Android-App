// Hides the Blocks editor's startup connection modal before first paint; the
// auto-connect driver removes this rule once it has decided the modal's fate.
(function(){var ID='__calliopeCmHide';if(document.getElementById(ID))return;var s=document.createElement('style');s.id=ID;s.textContent='.ReactModal__Overlay:has([class*=\"connection-modal_\"]){display:none !important;}';(document.head||document.documentElement).appendChild(s);})();
