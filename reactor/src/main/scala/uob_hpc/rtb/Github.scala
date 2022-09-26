package uob_hpc.rtb

import uob_hpc.rtb.Pickler

import java.net.http.HttpClient

object Github {

  case class Release(
      url: String,
      html_url: String,
      assets_url: String,
      upload_url: String,
      tarball_url: String,
      zipball_url: String,
      id: Long,
      node_id: String,
      tag_name: String,
      target_commitish: String,
      name: Option[String],
      body: Option[String],
      draft: Boolean,
      prerelease: Boolean,
      created_at: String,
      published_at: String,
      author: Map[String, ujson.Value],
      assets: List[Asset]
  )

  case class Asset(
      url: String,
      id: Long,
      node_id: String,
      name: String,
      label: String,
      uploader: Map[String, ujson.Value],
      content_type: String,
      state: String,
      size: Long,
      download_count: Long,
      created_at: String,
      updated_at: String,
      browser_download_url: String
  )
  object Asset {
    import Pickler.*
    given Reader[Asset] = macroR
  }

  object Release {
    import Pickler.*
    given Reader[Release] = macroR

    def fetchReleases(owner: String, repo: String, maxPage: Int = Int.MaxValue): Vector[Release] = {
      val client = HttpClient.newHttpClient()
      Vector
        .unfold(1) {
          case -1                     => None
          case page if page > maxPage => None
          case page =>
            Rest
              .fetch[Vector[Release]](
                client,
                s"https://api.github.com/repos/$owner/$repo/releases?per_page=100&page=$page"
              )
              .map(xs => xs -> (if (xs.size < 100) -1 else page + 1))
        }
        .flatten
    }

  }

}
