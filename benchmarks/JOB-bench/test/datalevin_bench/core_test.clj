(ns datalevin-bench.core-test
  (:require [datalevin-bench.core :as sut]
            [datalevin.core :as d]
            [clojure.test :as t :refer [are deftest]]))

(deftest correct-results-test
  (let [conn (d/get-conn "db")
        db   (d/db conn)]
    (are [query result] (= (d/q query db) result)
      sut/q-1a  [["(A Warner Bros.-First National Picture) (presents)"
                  "A Clockwork Orange" 1934]]
      sut/q-1b  [["(Set Decoration Rentals) (uncredited)" "Disaster Movie" 2008]]
      sut/q-1c  [["(co-production)" "Intouchables" 2011]]
      sut/q-1d  [["(Set Decoration Rentals) (uncredited)" "Disaster Movie" 2004]]
      sut/q-2a  [["'Doc'"]]
      sut/q-2b  [["'Doc'"]]
      sut/q-2c  []
      sut/q-2d  [["& Teller"]]
      sut/q-3a  [["2 Days in New York"]]
      sut/q-3b  [["300: Rise of an Empire"]]
      sut/q-3c  [["& Teller 2"]]
      sut/q-4a  [["5.1" "& Teller 2"]]
      sut/q-4b  [["9.1" "Batman: Arkham City"]]
      sut/q-4c  [["2.1" "& Teller 2"]]
      sut/q-5a  []
      sut/q-5b  []
      sut/q-5c  [["11,830,420"]]
      sut/q-6a  [["Downey Jr., Robert" "Iron Man 3"]]
      sut/q-6b  [["based-on-comic" "Downey Jr., Robert" "The Avengers 2"]]
      sut/q-6c  [["Downey Jr., Robert" "The Avengers 2"]]
      sut/q-6d  [["based-on-comic" "Downey Jr., Robert" "2008 MTV Movie Awards"]]
      sut/q-6e  [["Downey Jr., Robert" "Iron Man 3"]]
      sut/q-6f  [["based-on-comic" "\"Steff\", Stefanie Oxmann Mcgaha" "& Teller 2"]]
      sut/q-7a  [["Antonioni, Michelangelo" "Dressed to Kill"]]
      sut/q-7b  [["De Palma, Brian" "Dressed to Kill"]]
      sut/q-7c  [["50 Cent" "\"Boo\" Arnold was born Earl Arnold in Hattiesburg, Mississippi in 1966. His father gave him the nickname 'Boo' early in life and it stuck through grade school, high school, and college. He is still known as \"Boo\" to family and friends.  Raised in central Texas, Arnold played baseball at Texas Tech University where he graduated with a BA in Advertising and Marketing. While at Texas Tech he was also a member of the Texas Epsilon chapter of Phi Delta Theta fraternity. After college he worked with Young Life, an outreach to high school students, in San Antonio, Texas.  While with Young Life Arnold began taking extension courses through Fuller Theological Seminary and ultimately went full-time to Gordon-Conwell Theological Seminary in Boston, Massachusetts. At Gordon-Conwell he completed a Master's Degree in Divinity studying Theology, Philosophy, Church History, Biblical Languages (Hebrew & Greek), and Exegetical Methods. Following seminary he was involved with reconciliation efforts in the former Yugoslavia shortly after the war ended there in1995.  Arnold started acting in his early thirties in Texas. After an encouraging visit to Los Angeles where he spent time with childhood friend George Eads (of CSI Las Vegas) he decided to move to Los Angeles in 2001 to pursue acting full-time. While in Los Angeles he has studied acting with Judith Weston at Judith Weston Studio for Actors and Directors.  Arnold's acting career has been one of steady development, booking co-star and guest-star roles in nighttime television. He guest-starred opposite of Jane Seymour on the night time television drama Justice. He played the lead, Michael Hollister, in the film The Seer, written and directed by Patrick Masset (Friday Night Lights).  He was nominated Best Actor in the168 Film Festival for the role of Phil Stevens in the short-film Useless. In Useless he played a US Marshal who must choose between mercy and justice as he confronts the man who murdered his father. Arnold's performance in Useless confirmed his ability to carry lead roles, and he continues to work toward solidifying himself as a male lead in film and television.  Arnold married fellow Texan Stacy Rudd of San Antonio in 2003 and they are now raising their three children in the Los Angeles area."]]
      sut/q-8a  [["Chambers, Linda" ".hack//Quantum"]]
      sut/q-8b  [["Chambers, Linda" "Dragon Ball Z: Shin Budokai"]]
      sut/q-8c  [["\"A.J.\"" "#1 Cheerleader Camp"]]
      sut/q-8d  [["\"Jenny from the Block\"" "#1 Cheerleader Camp"]]
      sut/q-9a  [["AJ" "Airport Announcer" "Blue Harvest"]]
      sut/q-9b  [["AJ" "Airport Announcer" "Bassett, Angela" "Blue Harvest"]]
      sut/q-9c  [["'Annette'" "2nd Balladeer" "Alborg, Ana Esther" "(1975-01-20)"]]
      sut/q-9d  [["!!!, Toy" "\"Cockamamie's\" Salesgirl" "Aaron, Caroline" "$15,000.00 Error"]]
      sut/q-10a [["Actor" "12 Rounds"]]
      sut/q-10b []
      sut/q-10c [["Himself" "Evil Eyes: Behind the Scenes"]]
      sut/q-11a [["Churchill Films" "followed by" "Batman Beyond"]]
      sut/q-11b [["Filmlance International AB" "follows" "The Money Man"]]
      sut/q-11c [["20th Century Fox Home Entertainment" "(1997-2002) (worldwide) (all media)" "24"]]
      sut/q-11d [["13th Street" "(1954) (UK) (TV)" "...denn sie wissen nicht, was sie tun"]]
      sut/q-12a [["10th Grade Reunion Films" "8.1" "3:20"]]
      sut/q-12b [["$10,000" "Birdemic: Shock and Terror"]]
      sut/q-12c [["\"Oh That Gus!\"" "7.1" "$1.11"]]
      sut/q-13a [["Afghanistan:24 June 2012" "1.0" "&Me"]]
      sut/q-13b [["501audio" "1.8" "5 Time Champion"]]
      sut/q-13c [["DL Sites" "1.8" "Champion"]]
      sut/q-13d [["\"O\" Films" "1.0" "#54 Meets #47"]]
      sut/q-14a [["1.0" "$lowdown"]]
      sut/q-14b [["6.4" "Of Dolls and Murder"]]
      sut/q-14c [["1.0" "$lowdown"]]
      sut/q-15a [["USA:1 June 2007" "Battlestar Galactica: The Resistance"]]
      sut/q-15b [["USA:27 April 2007" "RoboCop vs Terminator"]]
      sut/q-15c [["USA:1 April 2003" "24: Day Six - Debrief"]]
      ;; sut/q-15d []
      sut/q-16a [["Adams, Stan" "Carol Burnett vs. Anthony Perkins"]]
      sut/q-16b [["!!!, Toy" "& Teller"]]
      sut/q-16c [["\"Brooklyn\" Tony Danza" "(#1.5)"]]
      sut/q-16d [["\"Brooklyn\" Tony Danza" "(#1.5)"]]
      sut/q-17a [["B, Khaz"]]
      sut/q-17b [["Z'Dar, Robert"]]
      sut/q-17c [["X'Volaitis, John"]]
      sut/q-17d [["Abrahamsson, Bertil"]]
      sut/q-17e [["$hort, Too"]]
      sut/q-17f [["'El Galgo PornoStar', Blanquito"]]
      sut/q-18a [["$1,000" "10" "40 Days and 40 Nights"]]
      sut/q-18b [["Horror" "8.1" "Agorable"]]
      sut/q-18c [["Action" "10" "#PostModem"]]
      sut/q-19a [["Angeline, Moriah" "Blue Harvest"]]
      sut/q-19b [["Jolie, Angelina" "Kung Fu Panda"]]
      sut/q-19c [["Alborg, Ana Esther" ".hack//Akusei heni vol. 2"]]
      sut/q-19d [["Aaron, Caroline" "$9.99"]]
      sut/q-20a [["Disaster Movie"]]
      sut/q-20b [["Iron Man"]]
      sut/q-20c [["Abell, Alistair" "...And Then I..."]]
      sut/q-21a [["Det Danske Filminstitut" "followed by" "Der Serienkiller - Klinge des Todes"]]
      sut/q-21b [["Filmlance International AB" "followed by" "Hämndens pris"]]
      sut/q-21c [["Churchill Films" "followed by" "Batman Beyond"]]
      sut/q-22a [["01 Distribution" "2.1" "12 Rounds"]]
      sut/q-22b [["Boll Kino Beteiligungs GmbH & Co. KG" "3.0" "A Small Act"]]
      sut/q-22c [["01 Distribution" "1.9" "(#1.1)"]]
      sut/q-22d [["01 Distribution" "1.6" "(#1.1)"]]
      sut/q-23a [["movie" "The Analysts"]]
      sut/q-23b [["movie" "The Big Mope"]]
      sut/q-23c [["movie" "Dirt Merchant"]]
      sut/q-24a [["Additional Voices" "Baker, Andrea" "Baiohazâdo 6"]]
      sut/q-24b [["Tigress" "Jolie, Angelina" "Kung Fu Panda 2"]]
      )))
