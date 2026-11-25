package eu.intracherche.intracherche.services;

import eu.intracherche.intracherche.config.NerConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test — requires the NER service to be running at localhost:8001.
 * The text is page 65/117 of "Attentats du 13 novembre 2015 en France" (Wikipedia),
 * a footnote-heavy page used to validate that "Paris" is not missed despite noisy content.
 */
@Tag("integration")
class NerServiceIntegrationTest {

    private static final String NER_URL = "http://localhost:8001";

    private static final String TEXT =
            "82. Catherine Fournier, « Attentats de Paris : Bilal Hadfi, le jeune jihadiste du Stade de\n"
            + "France, amateur de jeux vidéo et de taekwondo (https://www.francetvinfo.fr/faits-diver\n"
            + "s/terrorisme/attaques-du-13-novembre-a-paris/enquete-sur-les-attentats-de-paris/atten\n"
            + "tats-de-paris-bilal-hadfi-le-jeune-jihadiste-du-stade-de-france-amateur-de-jeux-videos-e\n"
            + "t-de-taekwendo_1180181.html) », sur FranceTv Info, 21 décembre 2015 (consulté le\n"
            + "18 avril 2020).\n"
            + "83. AFP, « Le destin tragique de Manuel Dias, victime des attentats au Stade de France : « Tu\n"
            + "auras une place privilégiée pour voir le match » (http://www.lalibre.be/sports/football/l\n"
            + "e-destin-tragique-de-manuel-dias-victime-des-attentats-au-stade-de-france-tu-auras-un\n"
            + "e-place-privilegiee-pour-voir-le-match-578154f93570142c1445e80f) », sur La Libre,\n"
            + "9 juillet 2016 (consulté le 18 avril 2020).\n"
            + "84. R.B., « Attentats à Paris : Bilal Hadfi a essayé de rentrer à quatre reprises au Stade de\n"
            + "France (https://www.20minutes.fr/sport/1756587-20151228-attentats-paris-bilal-hadfi-es\n"
            + "saye-rentrer-quatre-reprises-stade-france) », sur 20 Minutes, 28 décembre 2015\n"
            + "(consulté le 18 avril 2020).\n"
            + "85. Geoffrey Bonnefoy, « Attentats de Paris : au Stade de France, « ils se sont loupés » (htt\n"
            + "p://www.lexpress.fr/actualite/societe/fait-divers/attentats-de-paris-au-stade-de-france-il\n"
            + "s-se-sont-loupes_1736581.html) », sur L'Express, 17 novembre 2015 (consulté le\n"
            + "18 avril 2020).\n"
            + "86. A.Bo et P.Ta, « Attentats de Paris : l'histoire du faux héros du Stade de France (http://rmc\n"
            + "sport.bfmtv.com/football/attentats-de-paris-l-histoire-du-faux-heros-du-stade-de-franc\n"
            + "e-930673.html) », sur BFM TV, 16 novembre 2015 (consulté le 18 avril 2020).\n"
            + "87. Patricia Tourancheau, « Attentats terroristes à Paris : récit d'une soirée de terreur (http\n"
            + "s://www.nouvelobs.com/attentats-terroristes-a-paris/20151114.OBS9431/attentats-terro\n"
            + "ristes-a-paris-recit-d-une-soiree-de-terreur.html) », sur L'Obs, 13 novembre 2015\n"
            + "(consulté le 18 avril 2020).\n"
            + "88. Aurélie Lebelle et J.Cl., « Attentats à Paris : 3 fusils et des chargeurs trouvés dans la Seat\n"
            + "à Montreuil (http://www.leparisien.fr/faits-divers/attentats-a-paris-des-armes-de-guerre-\n"
            + "retrouvees-dans-la-seat-a-montreuil-15-11-2015-5278619.php) », sur Le Parisien,\n"
            + "15 novembre 2015 (consulté le 18 avril 2020).\n"
            + "89. « Attentats du 13 novembre : ce que révèle l'enquête (http://www.lefigaro.fr/actualite-fra\n"
            + "nce/2015/12/27/01016-20151227ARTFIG00167-attentats-du-13-novembre-ce-que-revele-\n"
            + "l-enquete.php) », sur Le Figaro, 27 décembre 2015 (consulté le 18 avril 2020).\n"
            + "90. « Ils ont tiré en plein dans la foule en criant Allah Akbar » (http://fr.euronews.com/201\n"
            + "5/11/14/ils-ont-tire-en-plein-dans-la-foule-en-criant-allah-akbar/), sur Euronews,\n"
            + "14 novembre 2015.\n"
            + "91. Julien Ricotta, « Vidéo - Les Eagles Of Death Metal veulent être les premiers à rejouer au\n"
            + "Bataclan (http://www.europe1.fr/culture/video-attentats-lemouvante-confession-des-ea\n"
            + "gles-of-death-metal-2626125) », sur europe1.fr, 26 novembre 2015 (consulté le\n"
            + "18 avril 2020).\n"
            + "Attentats du 13 novembre 2015 en France — Wikipédia https://fr.wikipedia.org/wiki/Attentats_du_13_novembre_2...\n"
            + "65 of 117 5/23/26, 05:39";

    @Test
    void page65_attentats_extracts_paris_in_cities_and_departments() {
        NerConfig config = new NerConfig();
        config.setPythonUrl(NER_URL);

        NerService nerService = new NerService(RestClient.builder(), config);
        assumeTrue(nerService.isHealthy(), "NER service not running at " + NER_URL + " — skipping");

        NerService.GeolocationsResult result = nerService.extractGeolocations(TEXT);

        System.out.println("============================================================");
        System.out.println("  NER result — page 65/117 'Attentats du 13 novembre 2015'");
        System.out.println("============================================================");
        System.out.println("  cities      : " + result.cities());
        System.out.println("  departments : " + result.departments());
        System.out.println("  regions     : " + result.regions());
        System.out.println("  countries   : " + result.countries());
        System.out.println("  'Paris' in cities      : " + result.cities().contains("Paris"));
        System.out.println("  'Paris' in departments : " + result.departments().contains("Paris"));

        // "Paris" must appear as a standalone normalized token, not as part of a phrase
        assertThat(result.cities())
                .as("cities should contain standalone 'Paris', not a compound phrase")
                .contains("Paris")
                .noneMatch(s -> s.contains("Paris") && s.length() > "Paris".length());

        assertThat(result.departments())
                .as("departments should contain standalone 'Paris', not a compound phrase")
                .contains("Paris")
                .noneMatch(s -> s.contains("Paris") && s.length() > "Paris".length());
    }

    /**
     * Regression test for INSEE commune validation (France-only filter).
     *
     * Before the fix, spaCy would tag common nouns and product labels as GPE/LOC
     * in noisy OCR'd documents (e.g. shopping lists, catalogues), producing entries
     * like "Soins", "WC", "Huile" in geolocation_city. The INSEE communes list now
     * rejects any entity not found among the 32 000+ official French commune names.
     *
     * "Roquefort" must survive — it is a real French commune (Landes / Aveyron).
     * "Lyon" must survive — it is a real city explicitly mentioned in the text.
     * Foreign cities not in the INSEE list (e.g. "Bruxelles") are intentionally
     * excluded by this filter; that limitation is documented in gazetteer.py.
     */
    @Test
    void product_catalogue_text_filters_non_place_names_and_retains_real_communes() {
        NerConfig config = new NerConfig();
        config.setPythonUrl(NER_URL);

        NerService nerService = new NerService(RestClient.builder(), config);
        assumeTrue(nerService.isHealthy(), "NER service not running at " + NER_URL + " — skipping");

        // Mimics the kind of OCR'd catalogue / mixed-content page that produced false positives.
        // Words like "Soins", "WC", "Huile" are common nouns that spaCy occasionally mis-tags
        // as GPE/LOC when surrounding context is sparse or fragmented.
        String text =
                "Rayon épicerie fine\n"
                + "Comté · Vache qui rit · Petits suisses · Galette\n"
                + "Huile d'olive extra vierge 75 cl\n"
                + "Roquefort AOP 100 g\n"
                + "Soins du corps — WC nettoyant désinfectant\n"
                + "Produits fabriqués à Lyon et distribués partout en France.";

        NerService.GeolocationsResult result = nerService.extractGeolocations(text);

        System.out.println("============================================================");
        System.out.println("  NER result — catalogue false-positive / INSEE filter test");
        System.out.println("============================================================");
        System.out.println("  cities      : " + result.cities());
        System.out.println("  departments : " + result.departments());
        System.out.println("  regions     : " + result.regions());
        System.out.println("  countries   : " + result.countries());

        // Common nouns and product names must be rejected by the INSEE filter.
        assertThat(result.cities())
                .as("common nouns mis-tagged by spaCy must be filtered by the INSEE commune list")
                .doesNotContain("Soins", "WC", "Huile", "Comté", "Galette", "Vache");

        // No single-character or two-character string should survive.
        assertThat(result.cities())
                .as("strings shorter than 3 characters must be rejected")
                .noneMatch(s -> s.length() < 3);

        // "Roquefort" is a real INSEE commune and must not be filtered out.
        // (It is in the INSEE list for both Landes and Aveyron.)
        assertThat(result.cities())
                .as("Roquefort is a real French commune and must be retained by the INSEE filter")
                .contains("Roquefort");

        // "Lyon" is unambiguously a city in our gazetteer and must always be detected.
        assertThat(result.cities())
                .as("Lyon must be detected from the explicit mention in the text")
                .contains("Lyon");

        // "France" is in the COUNTRIES set and must reach geolocation_country.
        assertThat(result.countries())
                .as("France must be detected as a country")
                .contains("France");
    }
}
