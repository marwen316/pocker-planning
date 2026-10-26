# Planning Room — Poker planning

Application d’estimation collective : Angular 20, Spring Boot 3.5, Java 21, JAR exécutable sur `dev2`. Interface française responsive, détection de changements Angular sans Zone.js.

## Fonctionnement

À l’arrivée, saisir prénom, nom et rôle. Une salle commune rassemble les navigateurs connectés au même serveur.

- **Développeur** : choisit ou change son vote jusqu’à révélation ; peut révéler lorsque tous les développeurs actifs ont voté.
- **Visiteur** : ne vote pas ; peut révéler à tout moment, même avant la fin des votes.
- Cartes : `0, 1, 2, 3, 5, 8, 13, 21, 34` et pause café. Le café compte comme un vote, mais ne compte pas dans la moyenne.
- Un nouveau tour efface tous les votes ; chacun peut le lancer et renseigner une nouvelle tâche.
- Les votes des autres sont absents des réponses API avant révélation. Les droits sont contrôlés par le serveur.
- Synchronisation par WebSocket : une connexion persistante reçoit les changements ; aucun polling périodique. Le serveur envoie un message heartbeat toutes les 15 secondes sur cette même connexion. Départ explicite ou retrait après 60 secondes sans connexion WebSocket active après détection de la déconnexion. Un nouvel arrivant développeur doit voter avant une révélation par développeur.

## Branche dev2 : JAR, Docker et tunnel Cloudflare

Cette branche produit un **JAR exécutable Java 21** avec Angular intégré et Tomcat embarqué. La branche `main` conserve le livrable WAR pour Tomcat externe. Le JAR de `dev2` se lance à la racine `/`.

### Lancer avec Docker (sans JDK ni Node sur la machine)

Prérequis : Docker Engine/Docker Desktop avec Compose v2.

```sh
git switch dev2
docker compose up -d --build --wait
```

Ouvrir **http://localhost:8080/**. Pour choisir un autre port, copier `.env.example` vers `.env` (`cp .env.example .env` sous Linux/macOS, `Copy-Item .env.example .env` sous PowerShell), puis modifier `APP_PORT`.

```sh
docker compose logs -f app
docker compose down
```

L’image est `pocker-planning:dev2`. Le Dockerfile compile Angular dans une étape Node, exécute les tests Java dans une étape Maven/Java 21 et utilise un JRE 21 avec un utilisateur non privilégié pour le conteneur final. Le port est accessible uniquement depuis la machine locale ; le connecteur Cloudflare rejoint directement le service sur le réseau Compose.

### Construire et lancer le JAR sans Docker

Prérequis : JDK 21, Maven 3.9+, accès aux registres Maven/npm pendant la construction.

```sh
mvn clean verify
java -jar target/pocker-planning.jar
```

Ouvrir **http://localhost:8080/**. La CI produit l’artifact `pocker-planning-jar`, vérifie le scénario WebSocket réel et construit/teste l’image Docker. Aucune image n’est publiée dans un registre.

### Publier via un tunnel Cloudflare nommé (compatible WebSocket)

La synchronisation utilise maintenant **WebSocket**, sans SSE ni polling. Les connexions WebSocket sont [prises en charge par Cloudflare](https://developers.cloudflare.com/network/websockets/).

Un tunnel nommé permet une adresse stable. Pour un essai temporaire, après lancement **local HTTP sans l’overlay tunnel**, vous pouvez aussi exécuter `cloudflared tunnel --url http://localhost:8080` sur la machine hôte, puis ouvrir l’URL HTTPS affichée. Les cookies sont non-Secure dans ce mode temporaire local ; l’overlay de tunnel nommé active Secure.

1. Créer un tunnel nommé dans le tableau de bord Cloudflare. Un compte et un domaine géré par Cloudflare sont nécessaires pour cette procédure.
2. Ajouter une route publique, par exemple `planning.votre-domaine.fr`, avec le service HTTP **`http://app:8080`**. `app` est le nom du service Docker ; ne pas mettre `localhost` dans cette configuration.
3. Copier `.env.example` vers `.env` et y renseigner le jeton du connecteur dans `TUNNEL_TOKEN`. Ce fichier est ignoré par Git et exclu du contexte Docker. Ne pas partager le jeton.
4. Lancer :

```sh
docker compose -f compose.yaml -f compose.tunnel.yaml up -d --build --wait
docker compose -f compose.yaml -f compose.tunnel.yaml logs -f cloudflared
```

Accéder ensuite à **`https://planning.votre-domaine.fr/`**. Ce mode active les cookies Secure : utiliser l’URL HTTPS publique pour rejoindre la salle, pas l’adresse locale HTTP. Le tunnel n’est créé/public que lorsque vous avez configuré Cloudflare et lancé le connecteur avec votre propre jeton.

Pour arrêter le tunnel :

```sh
docker compose -f compose.yaml -f compose.tunnel.yaml down
```

Pour revenir au mode local HTTP :

```sh
docker compose -f compose.yaml -f compose.tunnel.yaml down
docker compose up -d --wait
```

Sans règle Cloudflare Access, toute personne connaissant l’URL peut rejoindre la salle. Pour un usage réservé à l’équipe, configurer une application Access sur ce nom de domaine. Les participants et votes restent en mémoire et sont effacés au redémarrage du conteneur.

Le frontend n’utilise ni CDN ni police distante. La construction et le téléchargement des images nécessitent Internet ; `cloudflared` communique avec Cloudflare pour fournir le tunnel.

Références : [création du tunnel](https://developers.cloudflare.com/tunnel/get-started/), [jeton cloudflared](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/configure-tunnels/run-parameters/).

## Développement

```sh
mvn -Dfrontend.skip=true spring-boot:run
cd frontend
npm ci
npm start
```

Ouvrir http://localhost:4200 ; le proxy redirige `/api` sur le backend. Pour compiler séparément : `npm run build`, puis `mvn -Dfrontend.skip=true verify` depuis la racine.

## Organisation

```text
backend/src/main/java/fr/codenova/planning/
  api/        Contrôleur REST et contrats d’entrée/sortie validés
  domain/     Rôles métier
  service/    Règles métier, état partagé et confidentialité des votes
backend/src/test/java/  Tests métier et intégration HTTP
frontend/src/app/
  core/       Modèles typés et service HTTP
  features/planning-room/  Écran de connexion et salle de vote
  app.component.ts  Point d’entrée de l’application
frontend/src/styles.css  Design responsive et mouvements réduits
```

## Périmètre et limites

Version initiale pour une équipe de confiance : rôles autodéclarés, pas d’authentification d’entreprise. Une session HTTP identifie le participant avec cookie HttpOnly/SameSite Strict. Deux onglets d’un même navigateur partagent la même identité ; utiliser deux profils ou une fenêtre privée pour tester plusieurs personnes.

Les données restent **en mémoire** : un redémarrage efface la salle. Déployer une seule instance ; plusieurs instances nécessiteraient un stockage partagé. Pas d’historique ni de gestion de plusieurs salles. La moyenne est indicative et exclut les cartes café. Toutes les ressources sont servies par le JAR ; polices système locales, sans Google Fonts ni CDN. Aucun accès Internet nécessaire à l’exécution. La construction initiale utilise les registres Maven/npm.

## Références

- [JAR exécutable Spring Boot](https://docs.spring.io/spring-boot/maven-plugin/packaging.html)
- [Documentation Angular](https://angular.dev/overview)
- [Tomcat 10.1](https://tomcat.apache.org/tomcat-10.1-doc/)


## WebSocket et identification de la version

La version **1.0.0-rc.3** affiche `RC.3` près du logo. Les textes principaux et boutons font 18px par défaut ; les informations secondaires font au moins 15,75px. Après redéploiement, vérifier ce badge et recharger avec Ctrl+F5.

`/api/session/ws` reçoit une connexion WebSocket authentifiée par la session HTTP existante. Le serveur envoie un état personnalisé (`type: state`) à chaque connexion et changement. Les votes restent confidentiels jusqu’à révélation. Le navigateur choisit `ws://` en local HTTP et `wss://` en HTTPS, avec reconnexion automatique après coupure et snapshot complet. Le départ ferme la connexion ; le serveur maintient les connexions inactives par un heartbeat, sans nouvelles requêtes HTTP.

Dans F12 → Réseau → WS, la connexion doit obtenir **101 Switching Protocols** et recevoir des messages. Si un proxy autre que Cloudflare est ajouté, autoriser l’Upgrade WebSocket. Exemple Nginx :

```nginx
location /api/session/ws {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_read_timeout 60s;
}
```

Le front ne charge aucune ressource externe. Les commandes de vote et de révélation restent des appels HTTP uniquement sur action utilisateur.

Validation WebSocket du JAR, sans dépendance Python externe : `python3 scripts/smoke-websocket.py` (Java 21 dans le PATH). Ce scénario est aussi exécuté dans la CI.
