"""
Compare fr_core_news_md vs fr_core_news_lg on a real page extracted from the
Wikipedia article "Attentats du 13 novembre 2015 en France" (page 65/117).

This page is a reference/footnote page — exactly the kind of noisy text where the
md model is known to miss "Paris" as a location entity while tagging many false
positives (e.g. "Camembert", "AKKS-47", "procureur de la").

Run:
    cd ner-service
    uv run python test_ner_models.py

Install lg model first if needed:
    uv run python -m spacy download fr_core_news_lg
"""

import sys
import spacy
from gazetteer import classify, normalize, CITIES, DEPARTMENTS

NER_LABELS = {"GPE", "LOC"}
MAX_TEXT_CHARS = 100_000

# Page 65 content as provided during indexing
TEXT = (
    "82. Catherine Fournier, « Attentats de Paris : Bilal Hadfi, le jeune jihadiste du Stade de\n"
    "France, amateur de jeux vidéo et de taekwondo (https://www.francetvinfo.fr/faits-diver\n"
    "s/terrorisme/attaques-du-13-novembre-a-paris/enquete-sur-les-attentats-de-paris/atten\n"
    "tats-de-paris-bilal-hadfi-le-jeune-jihadiste-du-stade-de-france-amateur-de-jeux-videos-e\n"
    "t-de-taekwendo_1180181.html) », sur FranceTv Info, 21 décembre 2015 (consulté le\n"
    "18 avril 2020).\n"
    "83. AFP, « Le destin tragique de Manuel Dias, victime des attentats au Stade de France : « Tu\n"
    "auras une place privilégiée pour voir le match » (http://www.lalibre.be/sports/football/l\n"
    "e-destin-tragique-de-manuel-dias-victime-des-attentats-au-stade-de-france-tu-auras-un\n"
    "e-place-privilegiee-pour-voir-le-match-578154f93570142c1445e80f) », sur La Libre,\n"
    "9 juillet 2016 (consulté le 18 avril 2020).\n"
    "84. R.B., « Attentats à Paris : Bilal Hadfi a essayé de rentrer à quatre reprises au Stade de\n"
    "France (https://www.20minutes.fr/sport/1756587-20151228-attentats-paris-bilal-hadfi-es\n"
    "saye-rentrer-quatre-reprises-stade-france) », sur 20 Minutes, 28 décembre 2015\n"
    "(consulté le 18 avril 2020).\n"
    "85. Geoffrey Bonnefoy, « Attentats de Paris : au Stade de France, « ils se sont loupés » (htt\n"
    "p://www.lexpress.fr/actualite/societe/fait-divers/attentats-de-paris-au-stade-de-france-il\n"
    "s-se-sont-loupes_1736581.html) », sur L'Express, 17 novembre 2015 (consulté le\n"
    "18 avril 2020).\n"
    "86. A.Bo et P.Ta, « Attentats de Paris : l'histoire du faux héros du Stade de France (http://rmc\n"
    "sport.bfmtv.com/football/attentats-de-paris-l-histoire-du-faux-heros-du-stade-de-franc\n"
    "e-930673.html) », sur BFM TV, 16 novembre 2015 (consulté le 18 avril 2020).\n"
    "87. Patricia Tourancheau, « Attentats terroristes à Paris : récit d'une soirée de terreur (http\n"
    "s://www.nouvelobs.com/attentats-terroristes-a-paris/20151114.OBS9431/attentats-terro\n"
    "ristes-a-paris-recit-d-une-soiree-de-terreur.html) », sur L'Obs, 13 novembre 2015\n"
    "(consulté le 18 avril 2020).\n"
    "88. Aurélie Lebelle et J.Cl., « Attentats à Paris : 3 fusils et des chargeurs trouvés dans la Seat\n"
    "à Montreuil (http://www.leparisien.fr/faits-divers/attentats-a-paris-des-armes-de-guerre-\n"
    "retrouvees-dans-la-seat-a-montreuil-15-11-2015-5278619.php) », sur Le Parisien,\n"
    "15 novembre 2015 (consulté le 18 avril 2020).\n"
    "89. « Attentats du 13 novembre : ce que révèle l'enquête (http://www.lefigaro.fr/actualite-fra\n"
    "nce/2015/12/27/01016-20151227ARTFIG00167-attentats-du-13-novembre-ce-que-revele-\n"
    "l-enquete.php) », sur Le Figaro, 27 décembre 2015 (consulté le 18 avril 2020).\n"
    "90. « Ils ont tiré en plein dans la foule en criant Allah Akbar » (http://fr.euronews.com/201\n"
    "5/11/14/ils-ont-tire-en-plein-dans-la-foule-en-criant-allah-akbar/), sur Euronews,\n"
    "14 novembre 2015.\n"
    "91. Julien Ricotta, « Vidéo - Les Eagles Of Death Metal veulent être les premiers à rejouer au\n"
    "Bataclan (http://www.europe1.fr/culture/video-attentats-lemouvante-confession-des-ea\n"
    "gles-of-death-metal-2626125) », sur europe1.fr, 26 novembre 2015 (consulté le\n"
    "18 avril 2020).\n"
    "Attentats du 13 novembre 2015 en France — Wikipédia https://fr.wikipedia.org/wiki/Attentats_du_13_novembre_2...\n"
    "65 of 117 5/23/26, 05:39"
)


def run_ner(model_name: str) -> dict:
    try:
        nlp = spacy.load(model_name, disable=["parser", "senter", "morphologizer", "attribute_ruler", "lemmatizer"])
    except OSError:
        return None

    doc = nlp(TEXT[:MAX_TEXT_CHARS])

    cities, departments, regions, countries = [], [], [], []
    seen: set[str] = set()
    raw_entities = []

    for ent in doc.ents:
        raw_entities.append((ent.text, ent.label_))
        if ent.label_ not in NER_LABELS:
            continue
        name = normalize(ent.text)
        if not name or name in seen:
            continue
        seen.add(name)

        bucket = classify(name)
        if bucket == "city":
            cities.append(name)
            dept = CITIES.get(name)
            if dept and dept not in seen:
                seen.add(dept)
                departments.append(dept)
                region = DEPARTMENTS.get(dept)
                if region and region not in seen:
                    seen.add(region)
                    regions.append(region)
        elif bucket == "department":
            departments.append(name)
            region = DEPARTMENTS.get(name)
            if region and region not in seen:
                seen.add(region)
                regions.append(region)
        elif bucket == "region":
            regions.append(name)
        elif bucket == "country":
            countries.append(name)

    return {
        "raw_loc_gpe": [(t, l) for t, l in raw_entities if l in NER_LABELS],
        "cities": cities,
        "departments": departments,
        "regions": regions,
        "countries": countries,
    }


def print_result(model_name: str, result: dict | None) -> None:
    print(f"\n{'=' * 60}")
    print(f"  Model: {model_name}")
    print(f"{'=' * 60}")
    if result is None:
        print(f"  [NOT INSTALLED — run: uv run python -m spacy download {model_name}]")
        return
    print(f"  LOC/GPE entities detected ({len(result['raw_loc_gpe'])}):")
    for text, label in result["raw_loc_gpe"]:
        print(f"    [{label}] {text!r}")
    print(f"\n  cities      : {result['cities']}")
    print(f"  departments : {result['departments']}")
    print(f"  regions     : {result['regions']}")
    print(f"  countries   : {result['countries']}")

    paris_found = "Paris" in result["cities"] or "Paris" in result["departments"]
    print(f"\n  'Paris' extracted: {'YES' if paris_found else 'NO'}")
    print(f"  False positives (not a real place): ", end="")
    all_found = result["cities"] + result["departments"] + result["regions"] + result["countries"]
    known = set(CITIES) | set(DEPARTMENTS) | {"France", "Belgique", "Espagne", "Italie",
                                               "Royaume-Uni", "Pays-Bas", "Allemagne"}
    fp = [x for x in result["cities"] if x not in known]
    print(fp if fp else "none detected")


if __name__ == "__main__":
    print("NER model comparison — page 65/117 of 'Attentats du 13 novembre 2015'")
    print("(reference/footnote page — known to be noisy for NER models)")

    for model in ("fr_core_news_md", "fr_core_news_lg"):
        print_result(model, run_ner(model))

    print()
