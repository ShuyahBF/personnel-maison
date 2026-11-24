#!/usr/bin/env python3
"""AUDIT DES ÉCRITURES (1.76) — rejoue, dans un VRAI SQLite, toutes les écritures possibles de l'application sur LES 19 TABLES, avec le schéma exact de Room
(app/schemas/.../<version>.json) et les déclencheurs réels (SuiviModifs + JournalDonnees exportés par les tests unitaires).

Pourquoi : SQLite fait primer la clause de conflit de l'instruction (Room écrit « INSERT OR ABORT / REPLACE / IGNORE », « UPDATE OR ABORT ») sur celle du déclencheur ;
un déclencheur mal écrit n'échoue QUE dans certaines combinaisons (constaté en 1.64 à 1.74 sur l'enregistrement d'une opération). Ici, chaque table subit :
ajout (ABORT / REPLACE / IGNORE), modification (ABORT), « remplacement » d'une fiche existante, suppression (une fois, deux fois, en masse), ajout face à une ligne de suivi
PÉRIMÉE (même numéro), modification d'une fiche sans ligne de suivi, rejeu de l'installation des déclencheurs (anciens déclencheurs remplacés), puis contrôles de cohérence.
Usage (après `gradle :app:testDebugUnitTest`) :  python3 tools/audit_ecritures.py        → code de sortie 0 = tout passe, 1 = au moins un échec (détail affiché).
"""
import glob
import json
import re
import sqlite3
import sys

SCHEMAS = sorted(glob.glob("app/schemas/*/*.json"), key=lambda p: int(re.search(r"(\d+)\.json$", p).group(1)))
SCHEMA = json.load(open(SCHEMAS[-1]))["database"]
SQL_FILES = ["app/build/suivi_modifs.sql", "app/build/journal_donnees.sql"]


def instructions():
    out = []
    for f in SQL_FILES:
        out += [s for s in open(f, encoding="utf-8").read().split(";\n") if s.strip()]
    return out


ANCIENS = {  # déclencheurs de la 1.64 à 1.74 (clauses de conflit dans le déclencheur) : ceux d'un téléphone qui vient d'être mis à jour
    "ai": "CREATE TRIGGER `suivi_ai_{t}` AFTER INSERT ON `{t}` BEGIN INSERT OR REPLACE INTO `suivi_fiches` (`t`, `id`, `uid`, `maj`) VALUES ('{t}', NEW.`id`, lower(hex(randomblob(16))), 0); END",
    "au": "CREATE TRIGGER `suivi_au_{t}` AFTER UPDATE ON `{t}` BEGIN INSERT OR IGNORE INTO `suivi_fiches` (`t`, `id`, `uid`, `maj`) VALUES ('{t}', NEW.`id`, lower(hex(randomblob(16))), 0); UPDATE `suivi_fiches` SET `maj` = 1 WHERE `t` = '{t}' AND `id` = NEW.`id`; END",
    "ad": "CREATE TRIGGER `suivi_ad_{t}` AFTER DELETE ON `{t}` BEGIN INSERT OR REPLACE INTO `suivi_suppressions` (`t`, `uid`, `maj`) SELECT `t`, `uid`, 0 FROM `suivi_fiches` WHERE `t` = '{t}' AND `id` = OLD.`id`; DELETE FROM `suivi_fiches` WHERE `t` = '{t}' AND `id` = OLD.`id`; END",
}


def monde(rejouer=False, anciens=False):
    """Base vide avec le schéma EXACT de Room + déclencheurs (rejouer=True : installation faite DEUX fois, comme à chaque ouverture de l'application)."""
    db = sqlite3.connect(":memory:")
    for e in SCHEMA["entities"]:
        db.execute(e["createSql"].replace("${TABLE_NAME}", e["tableName"]))
        for i in e.get("indices", []):
            db.execute(i["createSql"].replace("${TABLE_NAME}", e["tableName"]))
    if anciens:  # téléphone mis à jour : les tables de suivi et les ANCIENS déclencheurs existent déjà, la réparation doit les remplacer
        db.execute("CREATE TABLE `suivi_fiches` (`t` TEXT NOT NULL, `id` INTEGER NOT NULL, `uid` TEXT NOT NULL, `maj` INTEGER NOT NULL, PRIMARY KEY(`t`, `id`))")
        db.execute("CREATE UNIQUE INDEX `index_suivi_fiches_uid` ON `suivi_fiches` (`uid`)")
        db.execute("CREATE TABLE `suivi_suppressions` (`t` TEXT NOT NULL, `uid` TEXT NOT NULL, `maj` INTEGER NOT NULL, PRIMARY KEY(`t`, `uid`))")
        for e in SCHEMA["entities"]:
            for sql in ANCIENS.values():
                db.execute(sql.format(t=e["tableName"]))
    for _ in range(2 if rejouer else 1):
        for s in instructions():
            db.execute(s)
    return db


def colonnes(e):
    return [(f["columnName"], f["affinity"], f["notNull"]) for f in e["fields"]]


def ligne(e, n):
    """Valeurs plausibles et DISTINCTES (n) pour toutes les colonnes sauf id (index uniques respectés)."""
    v = {}
    for nom, aff, nn in colonnes(e):
        if nom == "id":
            continue
        v[nom] = (n if aff == "INTEGER" else n + 0.5 if aff == "REAL" else f"t{n}")
    return v


def ecrire(db, verbe, table, vals):
    cols = ", ".join(f"`{c}`" for c in vals)
    marq = ", ".join("?" for _ in vals)
    db.execute(f"{verbe} INTO `{table}` ({cols}) VALUES ({marq})", list(vals.values()))


def audit(e, rejouer, anciens=False):
    t = e["tableName"]
    db = monde(rejouer, anciens)
    pas = []

    def etape(nom, f):
        try:
            f()
        except Exception as exc:  # noqa: BLE001
            pas.append(f"{nom} : {exc}")

    modifiable = next((c for c, a, nn in colonnes(e) if c != "id"), None)
    est_profil = t == "profil"
    # id explicite pour « profil » (clé sans AUTOINCREMENT)
    base = {**ligne(e, 1), **({"id": 1} if est_profil else {})}
    etape("ajout (INSERT OR ABORT)", lambda: ecrire(db, "INSERT OR ABORT", t, base))
    etape("modification (UPDATE OR ABORT)", lambda: db.execute(f"UPDATE OR ABORT `{t}` SET `{modifiable}` = `{modifiable}` WHERE id = (SELECT MIN(id) FROM `{t}`)"))
    etape("modification 2e fois", lambda: db.execute(f"UPDATE OR ABORT `{t}` SET `{modifiable}` = `{modifiable}` WHERE id = (SELECT MIN(id) FROM `{t}`)"))
    etape("modification sans clause (requête @Query)", lambda: db.execute(f"UPDATE `{t}` SET `{modifiable}` = `{modifiable}`"))
    etape("remplacement (INSERT OR REPLACE) d'une fiche existante", lambda: ecrire(db, "INSERT OR REPLACE", t, {**ligne(e, 1), "id": db.execute(f"SELECT MIN(id) FROM `{t}`").fetchone()[0]}))
    etape("remplacement 2e fois", lambda: ecrire(db, "INSERT OR REPLACE", t, {**ligne(e, 1), "id": db.execute(f"SELECT MIN(id) FROM `{t}`").fetchone()[0]}))
    etape("ajout (INSERT OR IGNORE) en double", lambda: ecrire(db, "INSERT OR IGNORE", t, {**ligne(e, 1), "id": db.execute(f"SELECT MIN(id) FROM `{t}`").fetchone()[0]}))
    if not est_profil:
        etape("2e ajout (INSERT OR ABORT)", lambda: ecrire(db, "INSERT OR ABORT", t, ligne(e, 2)))
        etape("ajout face à une ligne de suivi PÉRIMÉE", lambda: (db.execute("INSERT INTO suivi_fiches(t,id,uid,maj) VALUES (?, 9999, 'perime', 0)", [t]),
                                                                  ecrire(db, "INSERT OR ABORT", t, {**ligne(e, 3), "id": 9999})))
        etape("modification d'une fiche SANS ligne de suivi", lambda: (db.execute("DELETE FROM suivi_fiches WHERE t = ? AND id = 9999", [t]),
                                                                       db.execute(f"UPDATE OR ABORT `{t}` SET `{modifiable}` = `{modifiable}` WHERE id = 9999")))
    etape("suppression (DELETE)", lambda: db.execute(f"DELETE FROM `{t}` WHERE id = (SELECT MIN(id) FROM `{t}`)"))
    etape("suppression en double (rien à supprimer)", lambda: db.execute(f"DELETE FROM `{t}` WHERE id = -1"))
    etape("suppression en masse", lambda: db.execute(f"DELETE FROM `{t}`"))
    etape("ajout après suppression en masse", lambda: ecrire(db, "INSERT OR ABORT", t, {**ligne(e, 5), **({"id": 1} if est_profil else {})}))
    # cohérence : chaque fiche présente a UNE ligne de suivi et UN journal
    manque = db.execute(f"SELECT COUNT(*) FROM `{t}` m WHERE NOT EXISTS (SELECT 1 FROM suivi_fiches s WHERE s.t = ? AND s.id = m.id)", [t]).fetchone()[0]
    if manque:
        pas.append(f"cohérence : {manque} fiche(s) sans ligne de suivi")
    orphelines = db.execute(f"SELECT COUNT(*) FROM suivi_fiches s WHERE s.t = ? AND NOT EXISTS (SELECT 1 FROM `{t}` m WHERE m.id = s.id)", [t]).fetchone()[0]
    if orphelines:
        pas.append(f"cohérence : {orphelines} ligne(s) de suivi sans fiche")
    if db.execute("SELECT COUNT(*) FROM journal_donnees WHERE t = ?", [t]).fetchone()[0] == 0:
        pas.append("journal : aucune ligne notée")
    return pas


def main():
    echecs = 0
    for rejouer, anciens in ((False, False), (True, False), (False, True)):
        for e in SCHEMA["entities"]:
            for p in audit(e, rejouer, anciens):
                echecs += 1
                print(f"ÉCHEC [{e['tableName']}{' · déclencheurs rejoués' if rejouer else ''}{' · après réparation des anciens déclencheurs' if anciens else ''}] {p}")
    n = len(SCHEMA["entities"])
    print(f"Audit des écritures : {n} tables × 3 installations (neuve, rejouée, anciens déclencheurs réparés) — " + ("TOUT PASSE ✔" if not echecs else f"{echecs} ÉCHEC(S) ✘"))
    sys.exit(1 if echecs else 0)


if __name__ == "__main__":
    main()
