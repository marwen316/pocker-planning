# Planning Room — Poker planning

Application d’estimation collective : Angular 20, Spring Boot 3.5, Java 21, JAR exécutable sur `dev2`. Interface française responsive, détection de changements Angular sans Zone.js.

## Fonctionnement

À l’arrivée, saisir prénom, nom et rôle. Une salle commune rassemble les navigateurs connectés au même serveur.

- **Développeur** : choisit ou change son vote jusqu’à révélation ; peut révéler lorsque tous les développeurs actifs ont voté.
- **Visiteur** : ne vote pas ; peut révéler à tout moment, même avant la fin des votes.
- Cartes : `0, 1, 2, 3, 5, 8, 13, 21, 34` et pause café. Le café compte comme un vote, mais ne compte pas dans la moyenne.
- Un nouveau tour efface tous les votes ; chacun peut le lancer et renseigner une nouvelle tâche.
- Les votes des autres sont absents des réponses API avant révélation. Les droits sont contrôlés par le serveur.
- Synchronisation par SSE : une connexion persistante reçoit les changements ; aucun polling périodique. Le serveur envoie un commentaire keepalive toutes les 15 secondes sur cette même connexion. Départ explicite ou retrait après 60 secondes sans connexion SSE active après détection de la déconnexion. Un nouvel arrivant développeur doit voter avant une révélation par développeur.

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

Ouvrir **http://localhost:8080/**. La CI produit l’artifact `pocker-planning-jar`, vérifie le scénario SSE réel et construit/teste l’image Docker. Aucune image n’est publiée dans un registre.

### Publier via un tunnel Cloudflare nommé (compatible SSE)

**Ne pas utiliser un Quick Tunnel** (`cloudflared tunnel --url ...` / `trycloudflare.com`) : [Cloudflare indique que SSE n’y est pas pris en charge](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/). L’application conserve SSE, sans polling.

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


## SSE et identification de la version

La version **1.0.0-rc.2** affiche `RC.2` près du logo. Les textes principaux et boutons font 18px par défaut ; les informations secondaires font au moins 15,75px. Après redéploiement, vérifier ce badge et recharger avec Ctrl+F5.

`GET /api/session/events` fournit `text/event-stream` avec un état personnalisé par participant à chaque connexion et changement (arrivée, vote, révélation, nouveau tour, départ). Les votes restent confidentiels jusqu’à révélation. Le flux se ferme au départ, à la destruction de l’écran ou lorsque l’expiration de la session est détectée. EventSource se reconnecte après une coupure ; un snapshot complet resynchronise le navigateur. Le flux est renouvelé après 30 minutes pour revérifier la session HTTP. Un commentaire de maintien de connexion n’entraîne aucune nouvelle requête HTTP.

Le reverse proxy doit laisser passer `/api/session/events`, désactiver la mise en tampon et autoriser les connexions longues. Exemple Nginx (adapter la cible) :

```nginx
location /api/session/events {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 60s;
}
```

Dans l’onglet Réseau du navigateur, un flux `events` reste ouvert. Les requêtes de vote/révélation sont déclenchées uniquement par les actions utilisateur ; un GET initial restaure la session, et un GET de diagnostic peut suivre une erreur de flux. Aucune requête de ressource vers un domaine externe.

Référence : [Server-sent events (MDN)](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events).

Validation SSE du JAR, sans dépendance Python externe : `python3 scripts/smoke-sse.py` (Java 21 dans le PATH). Ce scénario est aussi exécuté dans la CI.
