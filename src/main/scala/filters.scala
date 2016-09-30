import org.apache.spark.SparkContext
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.Row
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.udf
import org.apache.spark.sql.functions._
import scala.collection.Searching._

object Filters {
  /*Filters for Muon DF*/
  // kPOGLooseMuon = 1, kPOGTightMuon = 2
  val muonpassUDF = udf {
    (kPOG: Int, chHadIso: Float, neuHadIso: Float, gammaIso: Float, puIso: Float, pogIDBits: Int, pt: Float) => {
     ((pogIDBits & kPOG)!=0  && ((chHadIso + Math.max(neuHadIso + gammaIso - 0.5*(puIso), 0)) < (0.12*pt)))
    }
  }

  def filterMuonDF(spark: SparkSession, kPOG: Int, muon_df: DataFrame) : DataFrame = {
    import spark.implicits._
    val fdf = muon_df.withColumn("passfilter", muonpassUDF(lit(kPOG), $"Muon_chHadIso", $"Muon_neuHadIso", $"Muon_gammaIso", $"Muon_puIso", $"Muon_pogIDBits", $"Muon_pt"))
    fdf.createOrReplaceTempView("muons")
    val fdf1 = spark.sql("SELECT * FROM muons WHERE Muon_pt >= 10 and Muon_eta > -2.4 and Muon_eta < 2.4 and passfilter")
    fdf1.show()
    fdf1
  }

  /*Filters for Tau DF*/
  // In the Princeton version, there is a call to passVeto function
  // which is not doing anything in this filter so I havenot implmented 
  // it
  var taupassUDF = udf {
    (hpsDisc: Long, rawIso3Hits: Float) => ((hpsDisc.toInt & (1 << 16)) != 0) && (rawIso3Hits <= 5)
  }

  def filterTauDF(spark: SparkSession, tau_df: DataFrame) : DataFrame = {
    import spark.implicits._
    val fdf = tau_df.withColumn("passfilter", taupassUDF($"Tau_hpsDisc", $"Tau_rawIso3Hits"))
    fdf.show()
    fdf.createOrReplaceTempView("taus")
    val fdf1 = spark.sql("SELECT * FROM taus WHERE Tau_pt >= 10 and Tau_eta > -2.3 and Tau_eta < 2.3 and passfilter")
    fdf1.show()
    fdf1
  }
  /*Utility function used by both photon and electron filters*/
  def EffArea(eta: Float, eta_range: Array[Float], eff_area: Array[Float]) : Float = {
    val searchtype = eta_range.search(eta)
    var index = searchtype.insertionPoint
    if (index == 0) return eff_area(index)
    if (searchtype.toString.startsWith("InsertionPoint")) index = index - 1
    return eff_area(index)
  }

  /*Filters for Photon DF*/
    var photonpassUDF = udf {
    (chHadIso: Float, neuHadIso: Float, gammaIso: Float, scEta: Float, sthovere: Float, sieie: Float, pt: Float, rhoarea0: Float, rhoarea1: Float, rhoarea2: Float) => {
      val chiso = Math.max((chHadIso - rhoarea0), 0.0)
      val neuiso = Math.max((neuHadIso - rhoarea1), 0.0)
      val phoiso = Math.max((gammaIso - rhoarea2), 0.0)

      if (sthovere <= 0.05) true
      (Math.abs(scEta) <= 1.479 && ((sieie    <= 0.0103) ||
                                    (chiso <= 2.44) ||
                                    (neuiso <= (2.57 + Math.exp(0.0044*pt*0.5809))) ||
                                    (phoiso <= (1.92+0.0043*pt)))) ||
      (Math.abs(scEta) > 1.479 &&  ((sieie    <= 0.0277) ||
                                    (chiso <= 1.84) ||
                                    (neuiso <= (4.00 + Math.exp(0.0040*pt*0.9402))) ||
                                    (phoiso <= (2.15+0.0041*pt))))
    }
  }
  
  def rhoeffareaPhoUDF(eta_range: Array[Float], eff_area:Array[Float]) = udf ((rho:Float, eta: Float) => rho*EffArea(eta, eta_range, eff_area))
 
  def filterPhotonDF(spark: SparkSession, photon_df: DataFrame) : DataFrame = {
    import spark.implicits._
    val eta_range = Array(0.0f, 1.0f, 1.479f, 2.0f, 2.2f, 2.3f, 2.4f)
    val eff_area_0 = Array(0.0157f, 0.0143f, 0.0115f, 0.0094f, 0.0095f, 0.0068f, 0.0053f)
    val eff_area_1 = Array(0.0143f, 0.0210f, 0.0147f, 0.0082f, 0.0124f, 0.0186f, 0.0320f)
    val eff_area_2 = Array(0.0725f, 0.0604f, 0.0320f, 0.0512f, 0.0766f, 0.0949f, 0.1160f)
    var fdf = photon_df.withColumn("rhoArea0", rhoeffareaPhoUDF(eta_range, eff_area_0)($"rhoIso", $"Photon_sceta"))
    fdf = fdf.withColumn("rhoArea1", rhoeffareaPhoUDF(eta_range, eff_area_1)($"rhoIso", $"Photon_sceta"))
    fdf = fdf.withColumn("rhoArea2", rhoeffareaPhoUDF(eta_range, eff_area_2)($"rhoIso", $"Photon_sceta"))
    fdf = fdf.withColumn("passfilter", photonpassUDF($"Photon_chHadIso", $"Photon_neuHadIso", $"Photon_gammaIso", $"Photon_scEta", $"Photon_sthovere", $"Photon_sieie", $"Photon_pt", $"rhoArea0", $"rhoArea1", $"rhoArea2"))
    fdf.show()
    fdf.createOrReplaceTempView("photons")
    val fdf1 = spark.sql("Select * FROM photons WHERE Photon_pt >= 175 and Photon_eta < 1.4442 and Photon_eta > -1.4442 and passfilter")
    fdf1.show()
    fdf1
  }

  /*Filters for Electron DF*/
  var elecpassUDF = udf {
    (pt: Float, isConv: Short,  chHadIso: Float, gammaIso: Float, neuHadIso: Float, rhoarea: Float, scEta: Float, dEtaIn: Float, dPhiIn: Float, sieie: Float) => {
      val iso = chHadIso + Math.max(0.0, (gammaIso + neuHadIso - rhoarea))
        if (isConv==1) false
        (((Math.abs(scEta) < 1.479) && ((iso                   < (0.126 * pt))         ||
                                       (Math.abs(dEtaIn)       < 0.01520) ||
                                       (Math.abs(dPhiIn)       < 0.21600)              ||
                                       (sieie                  < 0.01140))) ||
        ((Math.abs(scEta) >= 1.479) && ((iso                   < (0.144 * pt))         ||
                                       (Math.abs(dEtaIn)       < 0.01130) ||
                                       (Math.abs(dPhiIn)       < 0.23700)              ||
                                       (sieie                  < 0.03520))))
    }
  }

  var elecpass2UDF = udf {
    (scEta: Float, hovere: Float, eoverp: Float, ecalEnergy: Float, d0: Float, dz: Float, nMissingHits: Int) => {
        (((Math.abs(scEta) < 1.479) && (
                                       (hovere                 < 0.18100)              ||
                                       (Math.abs(1.0 - eoverp) < (0.20700*ecalEnergy)) ||
                                       (Math.abs(d0)           < 0.05640)              ||
                                       (Math.abs(dz)           < 0.47200)              ||
                                       (nMissingHits           <=  2))) ||
        ((Math.abs(scEta) >= 1.479) && (
                                       (hovere                 < 0.11600)              ||
                                       (Math.abs(1.0 - eoverp) < (0.17400*ecalEnergy)) ||
                                       (Math.abs(d0)           < 0.22200)              ||
                                       (Math.abs(dz)           < 0.92100)              ||
                                       (nMissingHits           <=  3))))
    }
  }
 
  var rhoeffareaUDF = udf {
    val eta_range = Array(0.0f, 0.8f, 1.3f, 2.0f, 2.2f, 2.3f, 2.4f)
    val eff_area = Array(0.1752f, 0.1862f, 0.1411f, 0.1534f, 0.1903f, 0.2243f, 0.2687f)
    (rho: Float, eta: Float) => rho*EffArea(eta, eta_range, eff_area)
  }

  def filterElectronDF(spark: SparkSession, e_df: DataFrame) : DataFrame = {
    import spark.implicits._
    var fdf = e_df.withColumn("rhoEffarea", rhoeffareaUDF($"rhoIso", $"Electron_eta"))
    fdf = fdf.withColumn("passfilter1", elecpassUDF($"Electron_pt", $"Electron_isConv", $"Electron_chHadIso", $"Electron_gammaIso", $"Electron_neuHadIso", $"rhoEffarea", $"Electron_scEta", $"Electron_dEtaIn", $"Electron_dPhiIn", $"Electron_sieie"))
    fdf = fdf.withColumn("passfilter2", elecpass2UDF($"Electron_scEta", $"Electron_hovere", $"Electron_eoverp", $"Electron_ecalEnergy", $"Electron_d0", $"Electron_dz", $"Electron_nMissingHits"))
    fdf.createOrReplaceTempView("Electrons")
    val fdf1 = spark.sql("SELECT * FROM Electrons WHERE Electron_pt >= 10 and Electron_eta < 2.5 and Electron_eta > -2.5 and passfilter1 and passfilter2")
    fdf1.show()
    fdf1
  }


  /*Filters for Jet and VJet*/
  var jetpassUDF = udf {
    (neuHadFrac: Float, neuEmFrac: Float, nParticles: Int, eta: Float, chHadFrac: Float, nCharged: Int, chEmFrac: Float) => {
       (neuHadFrac >= 0.99) || 
        neuEmFrac >= 0.99   || 
        nParticles <= 1     || 
        ((eta < 2.4 && eta > -2.4) && (chHadFrac == 0 || nCharged == 0 || chEmFrac >= 0.99))
    }
  }
  def filterJetDF(spark: SparkSession, jet_df: DataFrame) : DataFrame = {
    import spark.implicits._
    val fdf = jet_df.withColumn("passfilter", jetpassUDF($"AK4Puppi_neuHadFrac", $"AK4Puppi_neuEmFrac", $"AK4Puppi_nParticles", $"AK4Puppi_eta", $"AK4Puppi_chHadFrac", $"AK4Puppi_nCharged", $"AK4Puppi_chEmFrac"))
    fdf.show()
    fdf.createOrReplaceTempView("Jets")
    val fdf1 = spark.sql("SELECT * FROM Jets WHERE AK4Puppi_pt >=30 and AK4Puppi_eta < 4.5 and AK4Puppi_eta > -4.5 and passfilter")
    fdf1.show()
    fdf1
  }
}
