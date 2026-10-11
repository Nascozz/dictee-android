# Dictée

Speech-to-text pour Android, façon Spokenly : une petite bulle micro apparaît quand tu touches un champ de texte,
tu parles, et le texte s'écrit tout seul, dans n'importe quelle appli.

- **Fournisseurs** : Mistral (Voxtral), OpenAI, Groq, ElevenLabs, Deepgram, ou n'importe quel endpoint compatible OpenAI
- **Clés API** chiffrées dans le Keystore du téléphone, jamais envoyées ailleurs qu'au fournisseur choisi
- **Mises à jour automatiques** depuis les Releases GitHub

## Installer

1. Sur le téléphone, ouvre la [dernière Release](https://github.com/Nascozz/dictee-android/releases/latest) et télécharge l'APK.
2. Installe-le (autorise ton navigateur à installer des applis si Android le demande).
3. Ouvre Dictée et suis la checklist : micro, clé API, bulle (accessibilité), mises à jour.

> **« Paramètre restreint » ?** Android bloque l'accessibilité pour les applis installées hors Play Store.
> Réglages → Applis → Dictée → ⋮ → *Autoriser les paramètres restreints*, puis réactive la bulle.

## Utilisation

- Touche un champ de texte → la bulle apparaît.
- Touche la bulle → elle s'étire et enregistre (onde sonore).
- Touche à nouveau → transcription, puis le texte est inséré au curseur.
- Appui long pendant l'enregistrement → annuler.

## Comment marchent les mises à jour

Chaque push sur `main` lance [le workflow](.github/workflows/release.yml), qui compile un APK signé et crée une Release
`v1.0.<n>`. L'app vérifie GitHub toutes les 4 h (et à chaque ouverture), télécharge la nouvelle version et l'installe.

La clé de signature (`signing/`, non versionnée) est stockée dans les secrets GitHub `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD` et `KEY_ALIAS`. **Ne la perds pas** : sans elle, impossible de publier une mise à jour installable
par-dessus l'app existante.
