package com.unciv.logic.civilization

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector2
import com.unciv.logic.GameInfo
import com.unciv.logic.city.CityInfo
import com.unciv.logic.map.MapUnit
import com.unciv.logic.map.RoadStatus
import com.unciv.logic.map.TileInfo
import com.unciv.models.gamebasics.Civilization
import com.unciv.models.gamebasics.GameBasics
import com.unciv.models.gamebasics.TechEra
import com.unciv.models.gamebasics.tile.ResourceType
import com.unciv.models.gamebasics.tile.TileResource
import com.unciv.models.linq.Counter
import com.unciv.models.stats.Stats
import com.unciv.ui.utils.getRandom
import kotlin.math.max
import kotlin.math.pow


class CivilizationInfo {

    @Transient
    lateinit var gameInfo: GameInfo

    var gold = 0
    var happiness = 15
    var civName = "Babylon"

    var tech = TechManager()
    var policies = PolicyManager()
    var goldenAges = GoldenAgeManager()
    var greatPeople = GreatPersonManager()
    var scienceVictory = ScienceVictoryManager()

    var cities = ArrayList<CityInfo>()
    var exploredTiles = HashSet<Vector2>()

    fun getCivilization(): Civilization {return GameBasics.Civilizations[civName]!!}

    fun getCapital()=cities.first { it.isCapital() }

    fun isPlayerCivilization() =  gameInfo.getPlayerCivilization()==this
    fun isBarbarianCivilization() =  gameInfo.getBarbarianCivilization()==this


    // negative gold hurts science
    fun getStatsForNextTurn(): Stats {
        val statsForTurn = Stats()
        for (city in cities) statsForTurn.add(city.cityStats.currentCityStats)
        statsForTurn.happiness = getHappinessForNextTurn().toFloat()

        val transportationUpkeep = getTransportationUpkeep()
        statsForTurn.gold -= transportationUpkeep

        val unitUpkeep = getUnitUpkeep()
        statsForTurn.gold -= unitUpkeep

        if (policies.isAdopted("Mandate Of Heaven"))
            statsForTurn.culture += statsForTurn.happiness / 2

        if (statsForTurn.gold < 0) statsForTurn.science += statsForTurn.gold

        // if we have - or 0, then the techs will never be complete and the tech button
        // will show a negative number of turns and int.max, respectively
        if(statsForTurn.science<1) statsForTurn.science=1f

        return statsForTurn
    }

    private fun getUnitUpkeep(): Int {
        val baseUnitCost = 0.5f
        val freeUnits = 3
        var unitsToPayFor = getCivUnits()
        if(policies.isAdopted("Oligarchy")) unitsToPayFor = unitsToPayFor.filterNot { it.getTile().isCityCenter() }
        val totalPaidUnits = max(0,unitsToPayFor.count()-freeUnits)
        val gameProgress = gameInfo.turns/400f // as game progresses maintainance cost rises
        val cost = baseUnitCost*totalPaidUnits*(1+gameProgress)
        val finalCost = cost.pow(1+gameProgress/3) // Why 3? No reason.
        return finalCost.toInt()
    }

    private fun getTransportationUpkeep(): Int {
        var transportationUpkeep = 0
        for (it in gameInfo.tileMap.values.filterNot { it.isCityCenter() }) {
            when(it.roadStatus) {
                RoadStatus.Road -> transportationUpkeep += 1
                RoadStatus.Railroad -> transportationUpkeep += 2
            }
        }
        if (policies.isAdopted("Trade Unions")) transportationUpkeep *= (2 / 3f).toInt()
        return transportationUpkeep
    }

    // base happiness
    fun getHappinessForNextTurn(): Int {
        var happiness = 15
        var happinessPerUniqueLuxury = 5
        if (policies.isAdopted("Protectionism")) happinessPerUniqueLuxury += 1
        happiness += getCivResources().keys
                .count { it.resourceType === ResourceType.Luxury } * happinessPerUniqueLuxury
        happiness += cities.sumBy { it.cityStats.getCityHappiness().toInt() }
        if (buildingUniques.contains("Provides 1 happiness per social policy"))
            happiness += policies.getAdoptedPolicies().count { !it.endsWith("Complete") }
        return happiness
    }

    fun getCivResources(): Counter<TileResource> {
        val civResources = Counter<TileResource>()
        for (city in cities) civResources.add(city.getCityResources())
        return civResources
    }

    val buildingUniques: List<String>
        get() = cities.flatMap{ it.cityConstructions.getBuiltBuildings().map { it.unique }.filterNotNull() }.distinct()


    constructor()

    constructor(civName: String, startingLocation: Vector2, gameInfo: GameInfo) {
        this.civName = civName
        this.gameInfo = gameInfo
        tech.techsResearched.add("Agriculture")
        this.placeUnitNearTile(startingLocation, "Settler")
        this.placeUnitNearTile(startingLocation, "Scout")
    }

    fun setTransients() {
        goldenAges.civInfo = this
        policies.civInfo = this
        tech.civInfo = this

        for (unit in getCivUnits()) {
            unit.civInfo=this
            unit.setTransients()
        }


        for (cityInfo in cities) {
            cityInfo.setTransients()
            cityInfo.civInfo = this
        }
    }


    fun addCity(location: Vector2) {
        val newCity = CityInfo(this, location)
        newCity.cityConstructions.chooseNextConstruction()
    }

    fun endTurn() {
        val nextTurnStats = getStatsForNextTurn()
        policies.endTurn(nextTurnStats.culture.toInt())
        gold += nextTurnStats.gold.toInt()

        if (cities.size > 0) tech.nextTurn(nextTurnStats.science.toInt())

        for (city in cities.toList()) { // a city can be removed while iterating (if it's being razed) so we need to iterate over a copy
            city.endTurn()
            greatPeople.addGreatPersonPoints(city.getGreatPersonPoints())
        }

        val greatPerson = greatPeople.getNewGreatPerson()
        if (greatPerson != null) {
            addGreatPerson(greatPerson)
        }

        goldenAges.endTurn(happiness)
        getCivUnits().forEach { it.endTurn() }
        gameInfo.updateTilesToCities()
    }

    fun startTurn(){
        getViewableTiles() // adds explored tiles so that the units will be able to perform automated actions better
        for (city in cities)
            city.cityStats.update()
        happiness = getHappinessForNextTurn()
        getCivUnits().forEach { it.startTurn() }
    }

    fun addGreatPerson(greatPerson: String) {
        val randomCity = cities.getRandom()
        placeUnitNearTile(cities.getRandom().location, greatPerson)
        addNotification("A $greatPerson has been born!", randomCity.location, Color.GOLD)
    }

    fun placeUnitNearTile(location: Vector2, unitName: String): MapUnit {
        return gameInfo.tileMap.placeUnitNearTile(location, unitName, this)
    }

    fun getCivUnits(): List<MapUnit> {
        return gameInfo.tileMap.values.flatMap { it.getUnits() }.filter { it.owner==civName }
    }

    fun getViewableTiles(): List<TileInfo> {
        var viewablePositions = emptyList<TileInfo>()
        viewablePositions += cities.flatMap { it.getTiles() }
                        .flatMap { it.neighbors } // tiles adjacent to city tiles
        viewablePositions += getCivUnits()
                .flatMap { it.getViewableTiles()} // Tiles within 2 tiles of units
        viewablePositions.map { it.position }.filterNot { exploredTiles.contains(it) }.toCollection(exploredTiles)
        return viewablePositions.distinct()
    }

    fun addNotification(text: String, location: Vector2?,color: Color) {
        if(isPlayerCivilization())
            gameInfo.notifications.add(Notification(text, location,color))
    }

    override fun toString(): String {return civName} // for debug

    fun isDefeated()= cities.isEmpty() && !getCivUnits().any{it.name=="Settler"}
    fun getEra(): TechEra {
        return tech.techsResearched.map { GameBasics.Technologies[it]!! }
                .map { it.era() }
                .max()!!
    }
}

//enum class DiplomaticStatus{
//    Peace,
//    War
//}
//
//class DiplomacyManager {
//    lateinit var otherCivName:String
//    var status:DiplomaticStatus = DiplomaticStatus.Peace
//}