import { ReactNode } from 'react';
import "../app/globals.css";
import Image from "next/image";
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';

export default function HomeSection1() {
    return (
            <div className={"section1"}>
                <div className={"info_section"}>
                    <>
                    <p>Hi, I'm Hezron Nangulu, based in Nairobi</p>
                    <div className={"Job-title"}> I'M A SOFTWARE  </div>
                    <div className={"Job-title"}> DEVELOPER</div>
                    <p className={"Job-summary"}>I specialize in building robust web applications, crafting elegant UIs, and writing clean, maintainable code. Passionate about performance, accessibility, and seamless user experience.</p>
                    </>
                </div>
                <div className={"Socials"}>
                    <div className={"Socials_each"}>
                          <FontAwesomeIcon className={"Socials_icon"} icon={['fab', 'whatsapp']} />
                          <div className={"Socials_data"}>
                              <div> Whatsapp</div>
                              <div> +254 714 415 034</div>
                          </div>
                    </div>

                    <div  className={"Socials_each"}>
                        <FontAwesomeIcon className={"Socials_icon"} icon={["fab", "linkedin"]} />
                        <div className={"Socials_data"}>
                        <div> LinkedIn </div>
                        <div> <a style={{textDecoration: "none", color: "white", }} href="https://www.linkedin.com/in/hezron-wekesa-b58583216/" target="_blank" rel="noopener noreferrer"> Hezron Wekesa </a> </div>
                        </div>
                    </div>

                    <div className={"Socials_each"}>
                          <div className={"Socials_icon"} style={{fontWeight: "bold", fontSize: "30px", fontFamily: "Impact"}}> @ </div>
                          <div className={"Socials_data"}>
                              <div> Email</div>
                              <div> <a style={{textDecoration: "none", color: "white"}} href="mailto:hezron.w12@gmail.com" target="_blank" rel="noopener noreferrer"> hezron.w12@gmail.com </a> </div>
                          </div>
                    </div>
                </div>
                <div className={"image-container"}>
                <Image
                        className={"user_image"}
                         src="/images/me.png" // or a full remote URL if allowed in next.config.js
                         fill
                         alt=""
                    />
                </div>

            </div>
    );
}

export function HomeSection2() {
    return (
            <div className={"section2"}>

            </div>
    );
}