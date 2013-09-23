/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE
 * or https://OpenDS.dev.java.net/OpenDS.LICENSE.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE.  If applicable,
 * add the following below this CDDL HEADER, with the fields enclosed
 * by brackets "[]" replaced with your own identifying information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2013 ForgeRock AS
 */
package org.opends.server.types;

import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.*;

@Test(groups = { "precommit", "types" }, sequential = true)
@SuppressWarnings("javadoc")
public class HostPortTest extends TypesTestCase
{

  public void valueOfIPv4NoSpaces()
  {
    final String serverURL = "home:1";
    final HostPort hp = HostPort.valueOf(serverURL);
    assertThat(hp.getHost()).isEqualTo("home");
    assertThat(hp.getPort()).isEqualTo(1);
    assertThat(hp.toString()).isEqualTo(serverURL);
  }

  public void valueOfIPv4Spaces()
  {
    final String serverURL = "home:1";
    final HostPort hp = HostPort.valueOf("  " + serverURL + "  ");
    assertThat(hp.getHost()).isEqualTo("home");
    assertThat(hp.getPort()).isEqualTo(1);
    assertThat(hp.toString()).isEqualTo(serverURL);
  }

  public void valueOfEqualsHashCodeIPv4()
  {
    final HostPort hp1 = HostPort.valueOf("home:1");
    final HostPort hp2 = HostPort.valueOf(" home:1 ");
    assertThat(hp1).isEqualTo(hp2);
    assertThat(hp1.hashCode()).isEqualTo(hp2.hashCode());
  }

  public void valueOfIPv6Brackets()
  {
    final String hostName = "2001:0db8:85a3:0000:0000:8a2e:0370:7334";
    final String serverURL = "[" + hostName + "]:389";
    final HostPort hp = HostPort.valueOf(serverURL);
    assertThat(hp.getHost()).isEqualTo(hostName);
    assertThat(hp.getPort()).isEqualTo(389);
    assertThat(hp.toString()).isEqualTo(serverURL);
  }

  public void valueOfIPv6NoBrackets()
  {
    final String hostName = "2001:0db8:85a3:0000:0000:8a2e:0370:7334";
    final HostPort hp = HostPort.valueOf(hostName + ":389");
    assertThat(hp.getHost()).isEqualTo(hostName);
    assertThat(hp.getPort()).isEqualTo(389);
    assertThat(hp.toString()).isEqualTo("[" + hostName + "]:389");
  }

  public void valueOfEqualsHashCodeIPv6()
  {
    final String hostName = "2001:0db8:85a3:0000:0000:8a2e:0370:7334";
    final HostPort hp1 = HostPort.valueOf("[" + hostName + "]:389");
    final HostPort hp2 = HostPort.valueOf(" " + hostName + " : 389 ");
    assertThat(hp1).isEqualTo(hp2);
    assertThat(hp1.hashCode()).isEqualTo(hp2.hashCode());
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void valueOfNoPort()
  {
    HostPort.valueOf("host");
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void valueOfNoHost()
  {
    HostPort.valueOf(":389");
  }

  @Test(expectedExceptions = NumberFormatException.class)
  public void valueOfPortNotANumber()
  {
    HostPort.valueOf("host:port");
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void valueOfPortNumberTooSmall()
  {
    HostPort.valueOf("host:-1");
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void valueOfPortNumberTooBig()
  {
    HostPort.valueOf("host:99999999");
  }

}
