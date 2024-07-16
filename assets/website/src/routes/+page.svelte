<script>
    import * as m from "$lib/paraglide/messages.js"
    import { base } from '$app/paths';

  </script>
  
  <style>
    .highlight {
      background-color: #e7f3fe;
      padding: 0.2em 0.5em;
      border-radius: 0.2em;
    }
    .image-container {
      text-align: center;
      margin: 1em 0;
    }

    summary {
        font-weight: bold;
        margin-left: -1.1rem;
    }

    details {
        padding: 0.5em 1.5em;
    }
  </style>
  
  <article>
    <header>
      <h2>{m.ok_bright_dachshund_view()}</h2>
      <p>Diese Anleitung hilft dir, deinen Calliope mini mit der App zu verbinden und Programme zu übertragen. Folge einfach den unten stehenden Schritten.</p>
    </header>
  
    <section>
      <h3>1. Den Calliope mini vorbereiten</h3>
      <p>Um deinen Calliope mini vorzubereiten, gehe wie folgt vor:</p>
      <ul>
        <li><strong>Wenn du einen Calliope mini 1 oder 2 besitzt:</strong></li>
        <ul>
          <li>Übertrage per USB ein Programm mit <code>one-time-pairing</code> Erweiterung. Nur dann kann die erstmalige kopplung mit dem Android Gerät erfolgen.</li>
          <li>Bringe den mini mit <span class="highlight">AB + Reset</span> in den Bluetooth-Modus.</li>
        </ul>
      </ul>
      <ul>
        <li><strong>Wenn du einen Calliope mini 3 besitzt:</strong></li>
        <ul>
          <li>Bringe den mini durch 3-maliges drucken der <span class="highlight">Reset-Taste</span> in den Bluetooth-Modus.</li>
        </ul>
      </ul>
      <!-- <div class="image-container">
        <img src="path/to/usb-transfer-image.png" alt="USB-Übertragung" />
        <img src="path/to/bluetooth-mode-image.png" alt="Bluetooth-Modus" />
      </div> -->
    </section>
  
    <section>
      <h3>2. Verbindung herstellen</h3>
      <p>Um die Verbindung herzustellen, folge diesen Schritten:</p>
      <ol>
        <li>Tippe auf das Calliope-Symbol (Floating Button).</li>
        <li>Wähle "Verbindung".</li>
        <li>Zeichne in der App das Muster des Calliopes nach.</li>
        <li>Tippe auf den grünen Pairing-Button.</li>
        <div class="image-container">
            <img src="{base}/screenshots/pairing-button.png" alt="Pairing Button" />
        </div>
        <li><b>Calliope mini 1 und 2</b>
            <ol>
                <li>Die Verbindung wird ohne Kopplungsanfrage hergestellt.</li>
            </ol>
        </li>
        <li><b>Calliope mini 3</b>
            <ol>
                <li>Es erscheint eine Kopplungsanfrage, bestätige diese.</li>
                <li>Die Kopplungsanfrage erscheint erneut, bestätige abermals.</li>
            </ol>
        </li>
        <li>Nach der erfolgreichen Verbindung mit dem Calliope wechselt die Farbe des Calliope-Buttons von Turkis auf Grün.</li>
      </ol>
      <!-- <div class="image-container">
        <img src="path/to/calliope-symbol-image.png" alt="Calliope-Symbol" />
      </div> -->
    </section>
  
    <section>
      <h3>3. Programme Übertragen</h3>
      <p>Um Programme zu übertragen, beachte Folgendes:</p>
      <ul>
        <li>Die Übertragung wird automatisch gestartet, sobald in einem Editor die Datei geladen wird.</li>
        <li>Wenn Bluetooth nicht dauerhaft auf dem Calliope aktiv ist, muss der Calliope bei jeder Übertragung mit <span class="highlight">AB + Reset</span> in den Bluetooth-Modus versetzt werden. Das ist bei dem Calliope mini 1 immer, bei dem Calliope mini 2 und 3 bei verwendung bestimmter Erweiterungen (z.b. <span class="highlight">Radio</span>) der Fall.</li>
      </ul>
      <!-- <div class="image-container">
        <img src="path/to/progress-bar-image.png" alt="Fortschrittsbalken" />
      </div> -->
    </section>

    <section>
        <h3>FAQ zur Fehlerbehebung</h3>
        <details>
          <summary>Die Verbindung zum Calliope mini schlägt fehl. Was kann ich tun?</summary>
          <p>Überprüfe, ob der Calliope mini im Bluetooth-Modus ist (A&B + Reset).</p>
        </details>
        <details>
          <summary>Die Kopplungsanfrage wird nicht angezeigt. Was soll ich machen?</summary>
          <p>Starte den Calliope mini und die App neu. Versuche erneut, die Verbindung herzustellen. Achte darauf, ob in den Beanchrichtigungen eine Kopplungsanfrage auftaucht.</p>
        </details>
        <details>
          <summary>Die Übertragung des Programms bricht ab oder startet nicht. Woran könnte das liegen?</summary>
          <p>Stelle sicher, dass der Calliope mini sich im Bluetooth-Modus befindet und keine anderen Bluetooth-Geräte die Verbindung stören.</p>
        </details>
      </section>
  </article>
  