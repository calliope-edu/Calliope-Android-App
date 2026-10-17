(function(){
  if (window.__calliopeConnect) return;
  window.__calliopeConnect = true;
  var TAG = '[CalliopeConnect]';
  var MODAL = 'connectionModal';
  var OPEN_MODAL = 'scratch-gui/modals/OPEN_MODAL';
  var CLOSE_MODAL = 'scratch-gui/modals/CLOSE_MODAL';
  var GESTURE_MS = 2000;
  var DEFAULT_EXT = 'calliopeMini';

  // Track real user input so a modal opened by tapping the status
  // button (manual) is told apart from the automatic startup one.
  var lastGestureAt = 0;
  ['pointerdown','touchstart','mousedown','keydown'].forEach(function(ev){
    document.addEventListener(ev, function(){ lastGestureAt = Date.now(); }, true);
  });
  function gestureRecent(){ return (Date.now() - lastGestureAt) < GESTURE_MS; }

  // The early CSS (getConnectionModalHideCss) hides the startup
  // modal; create it here too in case that injection didn't land,
  // then drop it once startup is handled so manual opens show.
  function ensureHide(){
    if (document.getElementById('__calliopeCmHide')) return;
    var s = document.createElement('style'); s.id = '__calliopeCmHide';
    s.textContent = '.ReactModal__Overlay:has([class*="connection-modal_"]){display:none !important;}';
    (document.head || document.documentElement).appendChild(s);
  }
  function revealModal(){
    var s = document.getElementById('__calliopeCmHide');
    if (s && s.parentNode) s.parentNode.removeChild(s);
  }
  ensureHide();

  function isVM(o){
    try { return o && typeof o.connectPeripheral==='function'
      && typeof o.scanForPeripheral==='function'
      && typeof o.on==='function'; } catch(e){ return false; }
  }
  function findVMandStore(){
    try {
      var nodes = document.querySelectorAll('*'), anyFiber = null;
      for (var i=0; i<nodes.length && i<4000; i++){
        var el = nodes[i];
        var k = Object.keys(el).find(function(x){
          return x.indexOf('__reactFiber$')===0 || x.indexOf('__reactInternalInstance$')===0; });
        if (k){ anyFiber = el[k]; break; }
      }
      if (!anyFiber) return null;
      var root = anyFiber, g = 0;
      while (root.return && g++ < 5000) root = root.return;
      var stack = [root], seen = new Set(), visited = 0, store = null, vm = null;
      while (stack.length && visited < 60000){
        var f = stack.pop(); if (!f || seen.has(f)) continue; seen.add(f); visited++;
        var mp = f.memoizedProps, ms = f.memoizedState;
        if (mp){ if (isVM(mp.vm)) vm = mp.vm;
          if (mp.store && typeof mp.store.getState==='function') store = mp.store; }
        if (ms && isVM(ms.vm)) vm = ms.vm;
        if (f.child) stack.push(f.child);
        if (f.sibling) stack.push(f.sibling);
      }
      if (!vm && store){ try { var v = store.getState().scratchGui.vm; if (isVM(v)) vm = v; } catch(e){} }
      if (vm && store) return { vm: vm, store: store };
    } catch(e){}
    return null;
  }

  function modalOpen(store){
    try { return !!store.getState().scratchGui.modals[MODAL]; } catch(e){ return false; }
  }
  function extIdOf(store){
    try { return store.getState().scratchGui.connectionModal.extensionId || DEFAULT_EXT; }
    catch(e){ return DEFAULT_EXT; }
  }
  function isConnected(vm, extId){
    try { return !!(extId && vm.getPeripheralIsConnected(extId)); } catch(e){ return false; }
  }

  function install(vm, store){
    window.__calliopeVM = vm;
    var currentExt = null, connecting = false;

    // Native "Disconnect" (FAB menu). Going through scratch-vm
    // is what makes it a clean disconnect: the vm drops
    // `_connected` before closing the socket, so scratch-gui
    // shows plain "disconnected" instead of the connection-lost
    // modal — which this driver would otherwise treat as an
    // unexpected drop and silently reconnect.
    window.__calliopeDisconnect = function(){
      try {
        var ext = currentExt || extIdOf(store);
        if (isConnected(vm, ext)) vm.disconnectPeripheral(ext);
      } catch(e){}
    };

    // Auto-connect to the first peripheral seen during any scan —
    // whether we start it silently at launch or the user starts it
    // from the modal's Connect button.
    var origScan = vm.scanForPeripheral.bind(vm);
    vm.scanForPeripheral = function(extId){ currentExt = extId; connecting = false; return origScan(extId); };
    vm.on('PERIPHERAL_LIST_UPDATE', function(list){
      if (connecting || !currentExt || !list) return;
      if (isConnected(vm, currentExt)) return;
      var ids = Object.keys(list); if (!ids.length) return;
      var p = list[ids[0]]; if (!p || !p.peripheralId) return;
      connecting = true;
      console.log(TAG, 'auto-connecting', currentExt, p.peripheralId, p.name);
      try { vm.connectPeripheral(currentExt, p.peripheralId); } catch(e){ connecting = false; }
    });
    vm.on('PERIPHERAL_CONNECTED', function(){ connecting = false; console.log(TAG, 'connected'); });
    vm.on('PERIPHERAL_REQUEST_ERROR', function(){ connecting = false; console.log(TAG, 'request error'); });
    vm.on('PERIPHERAL_SCAN_TIMEOUT', function(){ connecting = false; });

    function startSilentScan(){
      var extId = extIdOf(store);
      if (isConnected(vm, extId)) return;
      // Small delay so a just-closed modal (iPad's scanning phase)
      // finishes tearing down before we (re)start the scan.
      setTimeout(function(){ try { vm.scanForPeripheral(extId); } catch(e){} }, 200);
    }

    // Native "Connect the mini" (FAB menu) after a disconnect: scan again,
    // the PERIPHERAL_LIST_UPDATE handler above connects to the first board.
    window.__calliopeConnectNow = function(){ startSilentScan(); };

    // Startup: the modal is open from initial state. Untouched by
    // the user -> close it and connect in the background.
    if (modalOpen(store) && !gestureRecent()){
      console.log(TAG, 'suppressing startup modal, connecting silently');
      store.dispatch({ type: CLOSE_MODAL, modal: MODAL });
      startSilentScan();
    }
    revealModal();

    // After startup the only opener is the user tapping the status
    // button — let those through. Anything that opens with no recent
    // gesture is closed and handled as a silent (re)connect.
    var prevOpen = modalOpen(store);
    store.subscribe(function(){
      var open = modalOpen(store);
      if (open && !prevOpen && !gestureRecent()){
        store.dispatch({ type: CLOSE_MODAL, modal: MODAL });
        startSilentScan();
      }
      prevOpen = open;
    });

    console.log(TAG, 'installed');
  }

  var tries = 0;
  var timer = setInterval(function(){
    var found = findVMandStore();
    if (found){ clearInterval(timer); install(found.vm, found.store); }
    else if (++tries > 60){ clearInterval(timer); revealModal(); console.log(TAG, 'vm/store not found'); }
  }, 500);
})();
