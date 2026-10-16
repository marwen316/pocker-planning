# Planning Room — Poker planning

Application d’estimation collective : Angular 20, Spring Boot 3.5, Java 21, WAR unique. Interface française responsive, détection de changements Angular sans Zone.js.

## Fonctionnement

À l’arrivée, saisir prénom, nom et rôle. Une salle commune rassemble les navigateurs connectés au même serveur.

- **Développeur** : choisit ou change son vote jusqu’à révélation ; peut révéler lorsque tous les développeurs actifs ont voté.
- **Visiteur** : ne vote pas ; peut révéler à tout moment, même avant la fin des votes.
- Cartes : `0, 1, 2, 3, 5, 8, 13, 21, 34` et pause café. Le café compte comme un vote, mais ne compte pas dans la moyenne.
- Un nouveau tour efface tous les votes ; chacun peut le lancer et renseigner une nouvelle tâche.
- Les votes des autres sont absents des réponses API avant révélation. Les droits sont contrôlés par le serveur.
- Synchronisation par SSE : une connexion persistante reçoit les changements ; aucun polling périodique. Le serveur envoie un commentaire keepalive toutes les 15 secondes sur cette même connexion. Départ explicite ou retrait après 60 secondes sans connexion SSE active après détection de la déconnexion. Un nouvel arrivant développeur doit voter avant une révélation par développeur.

## Construire le WAR

Prérequis : **JDK 21**, **Maven 3.9+**, accès aux registres Maven et npm. Maven installe Node 22 et npm pour le frontend.

```sh
mvn clean verify
java -jar target/pocker-planning.war
```

Ouvrir http://localhost:8080. Le WAR contient le frontend compilé et le backend. La CI GitHub Actions exécute les tests, compile Angular puis publie l’artifact `pocker-planning-war`.

## Déploiement Tomcat

Utiliser **Tomcat 10.1 avec Java 21**. Copier `target/pocker-planning.war` dans `webapps/`, puis ouvrir `https://votre-serveur/pocker-planning/` (barre finale). Le frontend utilise des URL relatives pour fonctionner sous ce contexte. Tomcat 9 utilise javax et ne convient pas.

En production HTTPS, définir `COOKIE_SECURE=true`. Configurer le reverse proxy et HTTPS ; ne pas exposer ce prototype directement à Internet sans restriction d’accès réseau.

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

Les données restent **en mémoire** : un redémarrage efface la salle. Déployer une seule instance ; plusieurs instances nécessiteraient un stockage partagé. Pas d’historique ni de gestion de plusieurs salles. La moyenne est indicative et exclut les cartes café. Toutes les ressources sont servies par le WAR ; polices système locales, sans Google Fonts ni CDN. Aucun accès Internet nécessaire à l’exécution. La construction initiale utilise les registres Maven/npm.

## Références

- [Déploiement WAR Spring Boot](https://docs.spring.io/spring-boot/how-to/deployment/traditional-deployment.html)
- [Documentation Angular](https://angular.dev/overview)
- [Tomcat 10.1](https://tomcat.apache.org/tomcat-10.1-doc/)


## SSE et identification de la version

La version **1.0.0-rc.2** affiche `RC.2` près du logo. Les textes principaux et boutons font 18px par défaut ; les informations secondaires font au moins 15,75px. Après redéploiement, vérifier ce badge et recharger avec Ctrl+F5.

`GET /api/session/events` fournit `text/event-stream` avec un état personnalisé par participant à chaque connexion et changement (arrivée, vote, révélation, nouveau tour, départ). Les votes restent confidentiels jusqu’à révélation. Le flux se ferme au départ, à la destruction de l’écran ou lorsque l’expiration de la session est détectée. EventSource se reconnecte après une coupure ; un snapshot complet resynchronise le navigateur. Le flux est renouvelé après 30 minutes pour revérifier la session HTTP. Un commentaire de maintien de connexion n’entraîne aucune nouvelle requête HTTP.

Le reverse proxy doit laisser passer `/pocker-planning/api/session/events`, désactiver la mise en tampon et autoriser les connexions longues. Exemple Nginx (adapter la cible) :

```nginx
location /pocker-planning/api/session/events {
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

Validation SSE du WAR, sans dépendance Python externe : `python3 scripts/smoke-sse.py` (Java 21 dans le PATH). Ce scénario est aussi exécuté dans la CI.
