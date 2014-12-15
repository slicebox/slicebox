package se.vgregion.db

import scala.slick.driver.JdbcProfile

class DAO(val driver: JdbcProfile) {
	
	val userDAO = new UserDAO(driver)

  val metaDataDAO = new MetaDataDAO(driver)
  
  val scpDataDAO = new ScpDataDAO(driver)
  
}