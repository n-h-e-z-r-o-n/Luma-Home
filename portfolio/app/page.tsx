import NavBar from '@/components/NavBar'
import HomeSection1  ,{ HomeSection2 }from '@/components/HomeSection1'

import "./globals.css"

export default function Page() {
  return (
      <>
       <NavBar />
       <div className={"main"}>
          <div className="snap-container">
              <section className="snap-section"><HomeSection1 /></section>
              <section className="snap-section"><HomeSection2 /></section>
           </div>
       </div>
      </>

  );
}

