import jade.core.Agent;
import jade.lang.acl.ACLMessage;
import jade.proto.ContractNetInitiator;

import java.util.Vector;

public class Initiator extends Agent {

    @Override
    protected void setup() {
        this.addBehaviour(new ContractNetInitiator(this, new ACLMessage(ACLMessage.CFP)) {
            @Override
            protected Vector prepareCfps(ACLMessage cfp) {
                /* prepare ACL message Call for Proposals */
                return super.prepareCfps(cfp);
            }

            @Override
            protected void handlePropose(ACLMessage propose, Vector acceptances) {
                /* handle proposal coming from responders */
                super.handlePropose(propose, acceptances);
            }

            @Override
            protected void handleRefuse(ACLMessage refuse) {
                /* handle refusals coming from responders */
                super.handleRefuse(refuse);
            }

            @Override
            protected void handleAllResponses(Vector responses, Vector acceptances) {
                /* when all replies to CFP have been collected, do something (select winner) */
                super.handleAllResponses(responses, acceptances);
            }

            @Override
            protected void handleInform(ACLMessage inform) {
                /* handle confirmations of tasks carried out coming from responders */
                super.handleInform(inform);
            }

        });
    }

}
