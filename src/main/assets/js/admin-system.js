(function () {
   'use strict';
}());

angular.module('slicebox.adminSystem', ['ngRoute'])

.config(function($routeProvider) {
  $routeProvider.when('/admin/system', {
    templateUrl: '/assets/partials/adminSystem.html',
    controller: 'AdminSystemCtrl'
  });
})

.controller('AdminSystemCtrl', function($scope, $http, openConfirmActionModal, openMessageModal) {

    $scope.clearLogButtonClicked = function() {
        openConfirmActionModal('System', 'This will delete all previous log messages. Proceed?', 'Ok', function() {
            return $http.delete('/api/log').then(function() {
                return openMessageModal('', 'The log has been cleared.');
            });
        });
    };

    $scope.exportAnonymizationInfoButtonClicked = function() {
        location.href = '/api/anonymization/keys/export/csv';
    };

    $scope.shutdownButtonClicked = function() {
        openConfirmActionModal('System', 'This command will wait for ongoing requests to finish and then stop the Slicebox service. Proceed?', 'Ok', function() {
            return $http.post('/api/system/stop').then(function() {
                return openMessageModal('', 'The Slicebox service has been stopped.');
            });
        });
    };

});
