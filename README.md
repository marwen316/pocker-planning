# Planning Room — Poker planning

Application d’estimation collective : Angular 20, Spring Boot 3.5, Java 21, WAR unique. Interface française responsive, détection de changements Angular sans Zone.js.

## Fonctionnement

À l’arrivée, saisir prénom, nom et rôle. Une salle commune rassemble les navigateurs connectés au même serveur.

- **Développeur** : choisit ou change son vote jusqu’à révélation ; peut révéler lorsque tous les développeurs actifs ont voté.
- **Visiteur** : ne vote pas ; peut révéler à tout moment, même avant la fin des votes.
- Cartes : `0, 1, 2, 3, 5, 8, 13, 21, 34` et pause café. Le café compte comme un vote, mais ne compte pas dans la moyenne.
- Un nouveau tour efface tous les votes ; chacun peut le lancer et renseigner une nouvelle tâche.
- Les votes des autres sont absents des réponses API avant révélation. Les droits sont contrôlés par le serveur.
- Synchronisation HTTP toutes les secondes. Départ explicite ou retrait après 60 secondes sans interrogation (y compris navigateur suspendu). Un nouvel arrivant développeur doit voter avant une révélation par développeur.

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

Les données restent **en mémoire** : un redémarrage efface la salle. Déployer une seule instance ; plusieurs instances nécessiteraient un stockage partagé. Pas d’historique ni de gestion de plusieurs salles. La moyenne est indicative et exclut les cartes café. La police Google Fonts dispose de polices système de secours si l’accès externe est indisponible.

## Références

- [Déploiement WAR Spring Boot](https://docs.spring.io/spring-boot/how-to/deployment/traditional-deployment.html)
- [Documentation Angular](https://angular.dev/overview)
- [Tomcat 10.1](https://tomcat.apache.org/tomcat-10.1-doc/)
