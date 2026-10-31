# Firebase (auth, firestore, messaging, functions), Room and WorkManager ship consumer rules in
# their AARs, so nothing is repeated here.
#
# No app classes need keeping for reflection: Firestore documents and callable payloads are built
# and read as Map<String, Any?> (sync/FirestoreMapping.kt), never through toObject() or POJO
# mapping, and the code never calls valueOf/enumValueOf on stored strings.
