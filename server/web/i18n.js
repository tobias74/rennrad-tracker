(() => {
  'use strict';

  const messages = {
    de: {
      homeTitle: 'Rennrad-Tracker · Live-Tracking für deine Familie',
      mapTitle: 'Live-Karte · Rennrad-Tracker',
      description: 'Teile deine Rennradfahrt live mit deiner Familie. Öffne die Karte mit einem Familienlink.',
      brand: 'Rennrad-Tracker', home: 'Startseite', language: 'Sprache',
      headline: 'Deine Ausfahrt.\nLive für deine Familie.',
      intro: 'Deine Familie sieht auf der Karte, wo du gerade bist, wie deine Strecke verläuft und wann deine Position zuletzt aktualisiert wurde.',
      openTitle: 'Fahrt ansehen', familyInput: 'Familienlink oder Familienkennung',
      placeholder: 'Link oder Kennung einfügen', enter: 'Live-Karte öffnen',
      inputHelp: 'Du bekommst den Familienlink von der Person, die ihre Fahrt in der Android-App teilt.',
      invalidInput: 'Gib einen gültigen Familienlink oder eine vollständige Familienkennung ein.',
      how: 'So funktioniert’s', startTitle: 'Fahrt starten', startInfo: 'Starte die Aufzeichnung in der Android-App.',
      shareTitle: 'Familienlink teilen', shareInfo: 'Teile den Link einmal. Er gilt auch für deine nächste Fahrt.',
      watchTitle: 'Live mitverfolgen', watchInfo: 'Der Link öffnet im Browser die Strecke, das Tempo und den Akkustand.',
      legal: 'Rechtliches', imprint: 'Impressum', privacy: 'Datenschutz',
      rides: 'Fahrten', allRides: 'Alle Fahrten', map: 'Karte der Fahrten', location: 'Standort und gefahrene Strecke',
      follow: 'Zum Fahrer', route: 'Ganze Strecke', routes: 'Alle Strecken',
      telemetry: 'Fahrtdaten', details: 'Fahrtdetails', speed: 'Tempo', battery: 'Akku', age: 'Standort',
      distance: 'Distanz', duration: 'Dauer', altitude: 'Höhe', accuracy: 'GPS-Genauigkeit',
      bearing: 'Fahrtrichtung', timestamp: 'Letzter Messpunkt', zoomIn: 'Vergrößern', zoomOut: 'Verkleinern',
      connecting: 'Verbindet …', loading: 'Die Fahrt wird geladen.', offline: 'Verbindung unterbrochen',
      noRide: 'Keine Fahrt', emptyTitle: 'Noch keine Fahrt', emptyText: 'Sobald eine Fahrt startet, erscheint sie hier.',
      noRoute: 'Keine Strecke verfügbar', noPoints: 'Für diese Fahrten liegen keine Standortpunkte vor.',
      waiting: 'Wartet auf GPS', firstFix: 'Der erste Standort erscheint hier.',
      stale: 'Position veraltet', removed: 'Handy entfernt', endedShort: 'Beendet', ended: 'Fahrt beendet',
      live: 'Live', liveCount: '{count} live', rideCount: '{count} Fahrten', firstPhone: 'Erstes Handy',
      secondsAgo: 'vor {seconds} s', durationAgo: 'vor {duration}',
      reconnecting: 'Verbindung wird hergestellt', reconnectText: 'Die Seite versucht es automatisch erneut.',
      renewed: 'Link erneuert', unavailable: 'Familienlink nicht verfügbar',
      askLink: 'Bitte die fahrende Person um den aktuellen Link.', back: 'Zur Startseite',
    },
    en: {
      homeTitle: 'Rennrad-Tracker · Live ride tracking for your family',
      mapTitle: 'Live map · Rennrad-Tracker',
      description: 'Share your road bike ride live with your family. Open the map with a family link.',
      brand: 'Rennrad-Tracker', home: 'Home', language: 'Language',
      headline: 'Your ride.\nLive for your family.',
      intro: 'Your family can see where you are, the route you’ve ridden, and when your location was last updated.',
      openTitle: 'Follow a ride', familyInput: 'Family link or family ID',
      placeholder: 'Paste a link or ID', enter: 'Open live map',
      inputHelp: 'Ask the rider for the family link they share from the Android app.',
      invalidInput: 'Enter a valid family link or the full family ID.',
      how: 'How it works', startTitle: 'Start your ride', startInfo: 'Start recording in the Android app.',
      shareTitle: 'Share your family link', shareInfo: 'Share the link once and use it again for your next ride.',
      watchTitle: 'Follow live', watchInfo: 'Open the link in a browser to see the route, speed, and battery level.',
      legal: 'Legal', imprint: 'Imprint', privacy: 'Privacy',
      rides: 'Rides', allRides: 'All rides', map: 'Ride map', location: 'Location and recorded route',
      follow: 'Follow rider', route: 'Full route', routes: 'All routes',
      telemetry: 'Ride data', details: 'Ride details', speed: 'Speed', battery: 'Battery', age: 'Location',
      distance: 'Distance', duration: 'Duration', altitude: 'Elevation', accuracy: 'GPS accuracy',
      bearing: 'Heading', timestamp: 'Last reading', zoomIn: 'Zoom in', zoomOut: 'Zoom out',
      connecting: 'Connecting…', loading: 'Loading the ride.', offline: 'Connection lost',
      noRide: 'No ride', emptyTitle: 'No rides yet', emptyText: 'A ride will appear here when it starts.',
      noRoute: 'No route available', noPoints: 'There are no location updates for these rides.',
      waiting: 'Waiting for GPS', firstFix: 'The first location update will appear here.',
      stale: 'Location out of date', removed: 'Device removed', endedShort: 'Ended', ended: 'Ride ended',
      live: 'Live', liveCount: '{count} live', rideCount: '{count} rides', firstPhone: 'First phone',
      secondsAgo: '{seconds} s ago', durationAgo: '{duration} ago',
      reconnecting: 'Reconnecting', reconnectText: 'The page will try again automatically.',
      renewed: 'Link renewed', unavailable: 'Family link unavailable',
      askLink: 'Ask the rider for the current link.', back: 'Back to home',
    },
  };

  function defaultLanguage() {
    const requested = new URL(location.href).searchParams.get('lang');
    if (requested === 'de' || requested === 'en') return requested;
    return navigator.language.toLowerCase().startsWith('en') ? 'en' : 'de';
  }

  let language = document.body.dataset.language
    || (document.querySelector('[data-i18n]') ? defaultLanguage() : document.documentElement.lang);
  const t = (key, values = {}) => messages[language][key].replace(/\{(\w+)\}/g, (_, name) => values[name]);

  function apply() {
    document.documentElement.lang = language;
    for (const element of document.querySelectorAll('[data-i18n]')) element.textContent = t(element.dataset.i18n);
    for (const [dataKey, attribute] of Object.entries({ label: 'aria-label', placeholder: 'placeholder', description: 'content' })) {
      for (const element of document.querySelectorAll(`[data-${dataKey}]`)) element.setAttribute(attribute, t(element.dataset[dataKey]));
    }
    for (const element of document.querySelectorAll('[data-legal-page]')) {
      element.href = element.dataset.legalPage === 'imprint'
        ? (language === 'de' ? '/impressum' : '/en/imprint')
        : (language === 'de' ? '/datenschutz' : '/en/privacy');
    }
    for (const element of document.querySelectorAll('[data-home]')) element.href = `/?lang=${language}`;
    for (const element of document.querySelectorAll('[data-select-language]')) {
      element.setAttribute('aria-pressed', String(element.dataset.selectLanguage === language));
    }
    document.dispatchEvent(new Event('tracker-language'));
  }

  for (const button of document.querySelectorAll('[data-select-language]')) {
    button.addEventListener('click', () => {
      language = button.dataset.selectLanguage;
      const url = new URL(location.href);
      url.searchParams.set('lang', language);
      history.replaceState(history.state, '', url);
      apply();
    });
  }
  window.trackerI18n = { t, get language() { return language; } };
  apply();
})();
