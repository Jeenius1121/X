# X Manager — gestionnaire de tweets pour Android

App Android hybride pour gérer son compte X **sans abonnement payant et sans risque de suspension** :
tout passe soit par le site officiel x.com, soit par l'API officielle de X (offre gratuite).

## Fonctions

| Onglet | Rôle |
|---|---|
| **X** | Ouvre x.com dans Chrome (onglet intégré) : fil, notifications, réponses, actions manuelles. |
| **Écrire** | Publier un tweet ou une réponse (lien/ID), ou le programmer à une date et heure. |
| **Gérer** | Rechercher dans tous ses tweets et réponses, puis **suppression en masse**. |
| **Réglages** | Connexion OAuth à X, import de l'archive, informations sur les quotas. |

### Recherche et suppression en masse
- Un des mots / tous les mots / phrase exacte / expression régulière.
- `"entre guillemets"` pour une expression, accents et majuscules ignorés par défaut, option « mot entier ».
- Filtres : tweets / réponses / retweets, « sans ces mots », réponses à un @compte, période, nombre de likes maximum.
- Sélection puis confirmation ; la suppression tourne en arrière-plan (WorkManager), se met en pause
  quand le quota de l'API est atteint et reprend toute seule, même app fermée.

## Pourquoi importer l'archive ?
L'offre gratuite de l'API X permet de **publier et supprimer**, mais pas de **lire** ses tweets.
La liste des tweets vient donc de l'archive officielle (x.com > Paramètres > Votre compte >
Télécharger une archive de vos données). Les tweets publiés depuis l'app sont ajoutés automatiquement.

## Mise en route
1. Sur developer.x.com : créer une app (offre Free), puis dans *User authentication settings* :
   permissions **Read and write**, type **Native App**, callback URI `arcx://callback`.
2. Dans l'app (Réglages) : coller le **Client ID**, puis « Se connecter à X ».
3. Importer l'archive `.zip`.

## Mises à jour
L'app vérifie à chaque ouverture la dernière *Release* GitHub du dépôt et propose de l'installer
par-dessus la version actuelle. Chaque push sur `main` publie automatiquement une nouvelle release
(si les secrets de signature sont configurés).

## Compilation
- Automatique : chaque push lance la GitHub Action *Build APK* ; l'APK est dans les *Artifacts* du run.
- Locale : `./gradlew assembleRelease` (SDK Android 35 requis) → `app/build/outputs/apk/release/app-release.apk`.

### Signature
Pour pouvoir installer les mises à jour par-dessus l'ancienne version, l'APK doit toujours être signé
avec la même clé. Ajoute dans GitHub (Settings > Secrets and variables > Actions) :
- `ARC_KEYSTORE_BASE64` : le fichier keystore encodé en base64 (`base64 -w0 sideload.keystore`)
- `ARC_KEYSTORE_PASSWORD` : son mot de passe (alias de clé : `arc`)

Sans ces secrets, l'APK est signé avec une clé de debug temporaire (il faudra désinstaller avant
chaque mise à jour). En local : placer la clé dans `keystore/sideload.keystore` et définir
`ARC_KEYSTORE_PASSWORD`. Le dossier `keystore/` n'est jamais versionné.
