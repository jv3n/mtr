# Benchmark fournisseurs de données de marché

Comparatif **Massive Advanced** vs **Alpaca Algo Trader Plus** pour la brique #9
(câbler la data temps réel). Établi le **2026-07-22**.

Contexte : TradeZero ne fournit **aucune** donnée de marché (exécution + locates + P&L
uniquement) — un fournisseur séparé est obligatoire. Voir `CLAUDE.md`.

> ⚠️ Les tarifs évoluent. Les prix ci-dessous ont été relevés le 2026-07-22 sur les pages
> officielles des deux fournisseurs. Re-vérifier avant de souscrire.

---

## 1. Ce dont le bot a réellement besoin

Le contrat est défini par `MarketDataProvider` (`src/main/kotlin/mtr/MarketData.kt:58`) :

```kotlin
interface MarketDataProvider {
    suspend fun getReference(ticker: String): TickerReference
    suspend fun getGainers(): List<MarketSnapshot>   // pilote le scanner autonome
    fun streamQuotes(tickers: List<String>): Flow<Quote>
}
```

D'où quatre exigences, par ordre d'importance :

| # | Exigence | Pourquoi | Consommateur |
|---|---|---|---|
| 1 | **Temps réel sur le tape complet (SIP)** | Détecter un retournement de pump exige le vrai volume et le vrai prix | `streamQuotes` |
| 2 | **Endpoint « top gainers » full-market** | Le scanner choisit son univers seul — c'est le cœur du « zéro intervention » | `getGainers` |
| 3 | **Stream avec VWAP + volume jour** | `Quote` porte `vwap`, `volume` (cumulé jour), `dayOpen`, `dayHigh` | `streamQuotes` |
| 4 | **Reference data (float / market cap)** | Proxy de float pour filtrer les small-caps | `getReference` |

**Le point 1 est éliminatoire.** Un flux IEX-only ne représente que ~2–4 % du volume sur une
small-cap : le VWAP et les volumes calculés dessus sont faux. Tout plan « temps réel IEX »
est donc hors sujet, quel que soit son prix.

---

## 2. Massive (ex-Polygon.io)

Fournisseur actuellement câblé (`MassiveProvider`). Rebrandé le 2025-10-30 ; les hostnames
`*.polygon.io` restent compatibles.

| Plan | Prix/mois | Données | WebSocket | Appels API | Snapshots |
|---|---|---|---|---|---|
| Basic | 0 $ | Fin de journée | ❌ | 5/min | ❌ |
| Starter | 29 $ | **Différé 15 min** | ✅ | illimité | ✅ |
| Developer | 79 $ | **Différé 15 min** | ✅ | illimité | ✅ |
| **Advanced** | **199 $** | **Temps réel** | ✅ | illimité | ✅ |

**Conclusion : pas de palier intermédiaire.** Chez Massive, le temps réel commence à 199 $.
Le plan Developer à 79 $ n'apporte rien de plus que Starter à 29 $ pour notre usage — les
deux sont différés. Payer 79 $ serait du gaspillage.

**Ce qui marche déjà** (code en place, schémas validés) :

- `GET /v2/snapshot/locale/us/markets/stocks/gainers` → `getGainers()`
- `GET /v3/reference/tickers/{ticker}` → `getReference()` (expose les *shares outstanding*,
  pas le vrai float → utilisé comme proxy)
- WebSocket agrégats-seconde `A.*` → `streamQuotes()`. Ces agrégats portent nativement
  **`a` = VWAP du jour** et **`av` = volume cumulé du jour**, ce qui alimente `Quote`
  directement, sans calcul côté bot.

---

## 3. Alpaca

| Plan | Prix/mois | Données | Feed | WebSocket | Appels API |
|---|---|---|---|---|---|
| Free | 0 $ | REST différé 15 min ; WS temps réel | **IEX seul** | 30 symboles max | 200/min |
| **Algo Trader Plus** | **99 $** | **Temps réel** | **Toutes places US (SIP)** | symboles illimités | illimité |

Le tier gratuit est **inutilisable** pour la détection (IEX-only, cf. §1). Seul le plan à
99 $ est pertinent.

**Endpoints screener** — c'est l'argument fort :

- `GET /v1beta1/screener/stocks/movers` — top gainers/losers, sur **SIP temps réel**
- `GET /v1beta1/screener/stocks/most-actives` — les plus actifs par volume ou nb de trades

Ils mappent directement sur `getGainers()`. Aujourd'hui `MassiveProvider` fait la même chose
via l'endpoint snapshot ; le remplacement est direct.

> ⚠️ **Piège documenté par Alpaca** : l'endpoint `movers` *« resets at market open, and until
> then, it shows the previous market day's movers »*. Un scan lancé à 7h ET renverrait donc
> les gainers **de la veille** — pas une liste vide, mais une liste **fausse**, ce qui est
> pire. Cela renforce la tâche « rejouer le scan après l'ouverture » de l'issue #9.

---

## 4. Comparatif

| Critère | Massive Advanced | Alpaca Algo Trader Plus |
|---|---|---|
| **Prix** | 199 $/mo | **99 $/mo** |
| Temps réel SIP (tape complet) | ✅ | ✅ |
| WebSocket, symboles illimités | ✅ | ✅ |
| Appels REST | illimité | illimité |
| Endpoint top gainers | ✅ snapshot `/gainers` | ✅ `screener/movers` |
| Endpoint most-actives | ➖ (dérivable) | ✅ natif |
| VWAP jour + volume cumulé dans le stream | ✅ natif (`a` / `av`) | ⚠️ à recalculer côté bot (exact, cf. §5) |
| Reference data float | ⚠️ shares outstanding (proxy) | ❌ **rien** |
| Short interest | ✅ **inclus dans tous les plans** | ❌ |
| Ownership institutionnel | ⚠️ 13F brut, par filer | ❌ |
| Historique | 7+ ans | 7+ ans |
| Code déjà écrit | ✅ `MassiveProvider` | ❌ à écrire |
| Compte de courtage requis | ❌ non | ⚠️ **à vérifier** |

**Économie annuelle si Alpaca convient : 1 200 $** (99 $ vs 199 $ sur 12 mois).

### 4.1 Couverture champ par champ

Vérification ciblée des cinq champs utilisés pour qualifier un candidat :

| Champ | Massive Advanced | Alpaca Algo Trader Plus |
|---|---|---|
| **volume** | ✅ Cumulé jour natif (`av` dans les agrégats `A.*`) | ✅ Dispo, mais **par barre** → à cumuler. `snapshot.dailyBar.v` donne le jour. |
| **% change** | ✅ L'endpoint `/gainers` le renvoie directement | ✅ `screener/movers` renvoie `percent_change` |
| **open** | ✅ Agrégats + snapshot | ✅ `snapshot.dailyBar.o` |
| **float** | ⚠️ **Proxy seulement** — `share_class_shares_outstanding` (*shares outstanding*, pas le float réel) | ❌ **Rien.** Absent de l'API, confirmé sur leur forum officiel |
| **% institutionnel** | ⚠️ **Pas exploitable tel quel** — voir ci-dessous | ❌ Rien |

**Sur le `% institutionnel`** : Massive expose bien les formulaires SEC 3, 4 et **13F**, mais
le 13F s'interroge **par `filer_cik`** (par gérant), pas par ticker. Obtenir « % détenu par
les institutions sur XYZ » supposerait d'agréger tous les filers — un travail conséquent.
Surtout, le 13F est **trimestriel avec ~45 jours de décalage** : sur un pump & dump
intraday de small-cap, l'information est structurellement périmée. **À considérer comme
indisponible pour notre usage**, chez les deux.

**Sur le `float`** : ni l'un ni l'autre ne donne le vrai float. Massive donne les *shares
outstanding* — un proxy imparfait mais utilisable ; Alpaca ne donne rien du tout. C'est
l'écart le plus net entre les deux, et un point à trancher : le float est un critère de
sélection central sur les small-caps.

### 4.2 Bonus Massive : le short interest

Massive expose un endpoint **short interest** (`/docs/rest/stocks/fundamentals/short-interest`)
qu'Alpaca n'a pas : `ticker`, *short interest* (nb de titres vendus à découvert non couverts),
**days to cover**, date de règlement, volume moyen journalier. Source FINRA, cadence
**bi-hebdomadaire**, historique depuis le 2017-12-29.

Deux choses à en retenir :

1. Pour un bot **short**, le *days to cover* est un indicateur de risque de squeeze
   directement pertinent — c'est exactement le risque majeur de cette stratégie.
2. ⚠️ **Cet endpoint est inclus dans TOUS les plans Stocks, y compris le Basic gratuit.**
   On peut donc l'exploiter **dès aujourd'hui**, sans rien payer et sans attendre la
   décision sur le temps réel.

La cadence bi-hebdomadaire en fait une donnée de contexte (filtrage / sizing), pas un signal
d'entrée — mais elle est gratuite.

---

## 5. Coût de portage vers Alpaca

L'interface `MarketDataProvider` a justement été conçue pour rester interchangeable — le
portage est confiné à une nouvelle classe `AlpacaProvider`, sans toucher à `Strategy`,
`RiskManager` ni `Main`.

| Méthode | Effort | Note |
|---|---|---|
| `getGainers()` | **Faible** | `screener/stocks/movers` → `MarketSnapshot`. Mapping direct. |
| `getReference()` | **Bloquant** | ❌ Alpaca ne fournit **pas** de float ni de shares outstanding. |
| `streamQuotes()` | **Moyen** | Agrégation de séance à écrire, cf. ci-dessous. |

**Sur `streamQuotes()` — ce n'est pas une perte d'information.** `Quote` attend `vwap` et
`volume` **cumulés sur la journée** (`MarketData.kt:34`). Massive les fournit tels quels dans
ses agrégats `A.*` (`a` / `av`). Les barres Alpaca portent le VWAP et le volume **de la
barre** (`vw` / `v`) — mais le VWAP de séance s'en reconstruit **exactement** :

```
VWAP_séance = Σ(vw_i × v_i) / Σ(v_i)
```

Il faut donc écrire une classe d'agrégation, avec remise à zéro à l'ouverture et
resynchronisation après reconnexion. Travail réel mais borné, et sans doute plus sain : le
VWAP devient explicite et testable au lieu d'être une valeur opaque du fournisseur.

**Sur `getReference()` — c'est le vrai point dur.** Alpaca n'expose ni float ni *shares
outstanding* (cf. §4.1). Le portage impliquerait donc soit d'abandonner le critère de float,
soit d'ajouter un **troisième** fournisseur juste pour la reference data — ce qui rogne
l'économie de 100 $/mois qui motivait le changement.

> À confirmer avant de s'engager : le schéma exact des barres WebSocket Alpaca (les docs
> `docs.alpaca.markets` ont renvoyé des 403 aux tentatives de vérification automatique).

---

## 6. Points ouverts — à lever avant de souscrire

1. **Alpaca vend-il la data seule ?** Nous tradons chez **TradeZero**. Il faut confirmer que
   l'abonnement Algo Trader Plus est accessible sans router d'ordres chez Alpaca. Ouvrir un
   compte paper Alpaca est gratuit et suffira peut-être à débloquer l'abonnement — **non
   vérifié**.
2. **Statut *non-professional subscriber*.** Les tarifs SIP grand public supposent un statut
   non-professionnel. Un usage automatisé pour compte propre reste normalement
   non-professionnel, mais à confirmer auprès d'Alpaca.
3. **Schéma des barres WebSocket** (cf. §5).
4. **Latence.** Aucun des deux ne publie de SLA. À mesurer en paper avant de trancher
   définitivement.

---

## 7. Recommandation

> **Révisée après vérification des champs (§4.1).** L'analyse tarifaire seule désignait
> Alpaca ; la couverture des données renverse la conclusion.

**Rester sur Massive, et passer à Advanced (199 $) le moment venu.**

L'écart de 100 $/mois est réel, mais Alpaca ne fournit **ni float ni shares outstanding**
(§4.1). Sur une stratégie small-cap où le float est un critère de sélection central,
supprimer cette donnée n'est pas un compromis acceptable — et la remplacer par un troisième
fournisseur de reference data annulerait une bonne partie de l'économie, tout en ajoutant
une dépendance et un mode de panne.

S'y ajoutent, dans le même sens :

- le **short interest** avec *days to cover*, absent chez Alpaca, directement pertinent pour
  évaluer le risque de squeeze — le risque principal de cette stratégie ;
- `MassiveProvider` est **déjà écrit et validé** ; le portage vers Alpaca coûterait une
  classe d'agrégation de séance plus un provider complet, pour un gain fonctionnel nul.

Alpaca reste le meilleur choix pour qui n'a pas besoin de fondamentaux — ce n'est pas
notre cas.

**Séquence suggérée** :

1. **Tout de suite, gratuitement** : câbler le **short interest** (inclus dans le plan Basic
   actuel, cf. §4.2). Aucun coût, information exploitable immédiatement pour le filtrage et
   le sizing.
2. Implémenter la tâche « re-scan après l'ouverture » de l'issue #9. **Prérequis** : sans
   elle, l'abonnement temps réel tourne à vide quel que soit le fournisseur.
3. Seulement ensuite, passer Massive de Starter (29 $) à **Advanced (199 $)** et lancer la
   fenêtre de mesure d'un mois.

**Ce qui pourrait rouvrir le débat** : si la mesure montre que le float ne discrimine
finalement pas les candidats, Alpaca redevient le choix rationnel — 1 200 $/an d'économie
pour une perte alors devenue théorique. À réévaluer après la fenêtre de mesure.

---

## Sources

- [Massive — Pricing](https://massive.com/pricing)
- [Alpaca — Market data plans](https://alpaca.markets/data)
- [Alpaca — Top market movers](https://docs.alpaca.markets/reference/movers-1)
- [Alpaca — Most active stocks](https://docs.alpaca.markets/reference/mostactives-1)
- [Alpaca — Screener SDK reference](https://alpaca.markets/sdks/python/api_reference/data/stock/screener.html)
- [Alpaca — Stock snapshots](https://docs.alpaca.markets/reference/stocksnapshots-1)
- [Alpaca — Forum officiel : « FLOAT We really need float »](https://forum.alpaca.markets/t/float-we-really-need-float/9101) (absence de float confirmée)
- [Massive — Short interest](https://massive.com/docs/rest/stocks/fundamentals/short-interest)
- [Massive — Insider & institutional ownership (formulaires 3, 4, 13F)](https://massive.com/blog/insider-and-institutional-ownership-data-via-massives-apis-forms-3-4-and-13f)
